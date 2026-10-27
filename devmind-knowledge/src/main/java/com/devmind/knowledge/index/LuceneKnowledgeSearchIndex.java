package com.devmind.knowledge.index;

import com.devmind.knowledge.config.KnowledgeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CAP-84 FR-09 嵌入式 Lucene 索引：BM25 文本 + HNSW 向量同索引，单写者 + SearcherManager
 * 近实时刷新。索引是派生数据：损坏/版本不匹配时可直接清空，由 DB 重建任务恢复。
 *
 * <p>向量字段按维度命名（vec_768/vec_1024），同一索引容纳多端点；查询只打当前维度字段。</p>
 */
@Component
public class LuceneKnowledgeSearchIndex implements KnowledgeSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(LuceneKnowledgeSearchIndex.class);
    static final String SCHEMA_VERSION = "3"; // CAP-94：去掉 projectId 字段（知识库去项目化）
    private static final String F_ENTRY_KEY = "entryKey";
    private static final String F_ENTRY_ID = "entryId";
    private static final String F_CHUNK_ID = "chunkId";
    private static final String F_KB_ID = "kbId";
    private static final String F_DOC_TYPE = "docType";
    private static final String F_STATUS = "status";
    private static final String F_TAG = "tag";
    private static final String F_TITLE = "title";
    private static final String F_HEADING = "heading";
    private static final String F_CONTENT = "content";
    private static final String F_VALID_TO = "validTo";
    private static final String F_VALID_FROM = "validFrom";
    private static final String F_UPDATED_AT = "updatedAt";

    private final KnowledgeProperties props;
    private final Object mutex = new Object();
    private final Set<Integer> vectorDims = new HashSet<>();

    private Path indexPath;
    private Directory directory;
    private Analyzer analyzer;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private int pendingDocs;

    public LuceneKnowledgeSearchIndex(KnowledgeProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Path path = Paths.get(props.getIndex().getPath()).toAbsolutePath().normalize();
        try {
            open(path);
        } catch (IOException e) {
            log.warn("Lucene 索引打开失败，清空重建索引目录: path={} err={}", path, e.toString());
            closeQuietly();
            deleteIndexFiles(path);
            try {
                open(path);
            } catch (IOException ex) {
                throw new IllegalStateException("无法初始化知识库 Lucene 索引: " + path, ex);
            }
        }
        log.info("知识库 Lucene 索引已就绪: path={} docs={} dims={}", path, numDocs(), vectorDims);
    }

    private void open(Path path) throws IOException {
        Files.createDirectories(path);
        indexPath = path;
        directory = new MMapDirectory(path);
        analyzer = new CJKAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(Math.max(16, props.getIndex().getRamBufferMb()));
        writer = new IndexWriter(directory, config);
        boolean exists = DirectoryReader.indexExists(directory);
        if (exists) {
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                String version = reader.getIndexCommit().getUserData().get("schemaVersion");
                if (!SCHEMA_VERSION.equals(version) && reader.numDocs() > 0) {
                    log.info("知识库索引 schema 版本不匹配（{} -> {}），清空等待重建", version, SCHEMA_VERSION);
                    writer.deleteAll();
                    writer.commit();
                }
            }
        }
        writer.setLiveCommitData(Map.of("schemaVersion", SCHEMA_VERSION).entrySet());
        writer.commit();
        searcherManager = new SearcherManager(writer, new SearcherFactory());
        refreshVectorDims();
    }

    @PreDestroy
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (searcherManager != null) {
                searcherManager.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        try {
            if (directory != null) {
                directory.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static void deleteIndexFiles(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // ignore
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
    }

    @Override
    public void upsert(EntryMeta entry, List<Chunk> chunks, List<float[]> vectors) {
        synchronized (mutex) {
            try {
                writer.deleteDocuments(new Term(F_ENTRY_KEY, Long.toString(entry.entryId())));
                if (chunks == null || chunks.isEmpty()) {
                    commitAndRefreshIfNeeded(true);
                    return;
                }
                long validTo = entry.validTo() == null ? 0L : entry.validTo().toEpochMilli();
                long validFrom = entry.validFrom() == null ? 0L : entry.validFrom().toEpochMilli();
                long updatedAt = entry.updatedAt() == null ? 0L : entry.updatedAt().toEpochMilli();
                List<Document> docs = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    Chunk chunk = chunks.get(i);
                    float[] vector = vectors != null && i < vectors.size() ? vectors.get(i) : null;
                    Document doc = new Document();
                    doc.add(new StringField(F_ENTRY_KEY, Long.toString(entry.entryId()), Field.Store.NO));
                    doc.add(new LongPoint(F_ENTRY_ID, entry.entryId()));
                    doc.add(new StoredField(F_ENTRY_ID, entry.entryId()));
                    doc.add(new LongPoint(F_CHUNK_ID, chunk.chunkId()));
                    doc.add(new StoredField(F_CHUNK_ID, chunk.chunkId()));
                    doc.add(new StringField(F_KB_ID, Long.toString(entry.kbId()), Field.Store.NO));
                    doc.add(new StringField(F_DOC_TYPE, entry.docType(), Field.Store.NO));
                    doc.add(new StringField(F_STATUS, entry.status(), Field.Store.NO));
                    if (entry.tags() != null) {
                        for (String tag : entry.tags()) {
                            if (tag != null && !tag.isBlank()) {
                                doc.add(new StringField(F_TAG, tag.toLowerCase(Locale.ROOT), Field.Store.NO));
                            }
                        }
                    }
                    doc.add(new TextField(F_TITLE, safe(entry.title()), Field.Store.NO));
                    doc.add(new TextField(F_HEADING, safe(chunk.headingPath()), Field.Store.NO));
                    doc.add(new TextField(F_CONTENT, safe(chunk.content()), Field.Store.NO));
                    doc.add(new LongPoint(F_VALID_TO, validTo));
                    doc.add(new LongPoint(F_VALID_FROM, validFrom));
                    doc.add(new LongPoint(F_UPDATED_AT, updatedAt));
                    if (vector != null && vector.length > 0) {
                        doc.add(new KnnFloatVectorField(vectorField(vector.length), vector,
                                VectorSimilarityFunction.COSINE));
                        vectorDims.add(vector.length);
                    }
                    docs.add(doc);
                }
                writer.addDocuments(docs);
                pendingDocs += docs.size();
                commitAndRefreshIfNeeded(false);
            } catch (IOException e) {
                throw new IllegalStateException("写入知识库索引失败: entry=" + entry.entryId(), e);
            }
        }
    }

    @Override
    public void deleteEntry(long entryId) {
        synchronized (mutex) {
            try {
                writer.deleteDocuments(new Term(F_ENTRY_KEY, Long.toString(entryId)));
                pendingDocs++;
                commitAndRefreshIfNeeded(false);
            } catch (IOException e) {
                throw new IllegalStateException("删除知识库索引失败: entry=" + entryId, e);
            }
        }
    }

    @Override
    public void deleteAll() {
        synchronized (mutex) {
            try {
                writer.deleteAll();
                writer.commit();
                searcherManager.maybeRefresh();
                vectorDims.clear();
                pendingDocs = 0;
            } catch (IOException e) {
                throw new IllegalStateException("清空知识库索引失败", e);
            }
        }
    }

    @Override
    public void flush() {
        synchronized (mutex) {
            try {
                writer.commit();
                searcherManager.maybeRefresh();
                pendingDocs = 0;
            } catch (IOException e) {
                throw new IllegalStateException("提交知识库索引失败", e);
            }
        }
    }

    private void commitAndRefreshIfNeeded(boolean force) throws IOException {
        int threshold = Math.max(20, props.getIndex().getCommitEveryChunks());
        if (force || pendingDocs >= threshold) {
            writer.commit();
            searcherManager.maybeRefresh();
            pendingDocs = 0;
        }
    }

    /**
     * 读前刷新：写入只按阈值 commit，若等 commit 才刷新 searcher，少量文档重建后
     * 检索会一直看到启动时的空快照（numDocs=0 → INDEX_EMPTY）。读时补一次 NRT
     * refresh 既不拖慢批量导入，又能保证「写完即可检索」。
     */
    private void refreshIfPending() {
        synchronized (mutex) {
            if (pendingDocs <= 0) {
                return;
            }
            try {
                searcherManager.maybeRefresh();
            } catch (IOException e) {
                log.warn("知识库索引刷新失败（按已有快照检索）: {}", e.toString());
            }
        }
    }

    @Override
    public List<RankedHit> searchText(Filter filter, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        refreshIfPending();
        Query textQuery = parseTextQuery(query);
        if (textQuery == null) {
            return List.of();
        }
        Query q = new BooleanQuery.Builder()
                .add(textQuery, BooleanClause.Occur.MUST)
                .add(filterQuery(filter, false), BooleanClause.Occur.FILTER)
                .build();
        return search(q, topK);
    }

    @Override
    public List<RankedHit> searchVector(Filter filter, float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        refreshIfPending();
        String field = vectorField(queryVector.length);
        if (!vectorDims.contains(queryVector.length)) {
            return List.of();
        }
        try {
            Query q = new KnnFloatVectorQuery(field, queryVector, Math.max(1, topK),
                    filterQuery(filter, true));
            return search(q, topK);
        } catch (Exception e) {
            log.warn("知识库向量检索失败（按无命中降级）: dim={} err={}", queryVector.length, e.toString());
            return List.of();
        }
    }

    private List<RankedHit> search(Query query, int topK) {
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            TopDocs top = searcher.search(query, Math.max(1, topK));
            StoredFields stored = searcher.storedFields();
            List<RankedHit> hits = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = stored.document(sd.doc);
                Number chunkId = doc.getField(F_CHUNK_ID) == null ? null : doc.getField(F_CHUNK_ID).numericValue();
                Number entryId = doc.getField(F_ENTRY_ID) == null ? null : doc.getField(F_ENTRY_ID).numericValue();
                if (chunkId == null || entryId == null) {
                    continue;
                }
                hits.add(new RankedHit(chunkId.longValue(), entryId.longValue(), 0L, sd.score));
            }
            return hits;
        } catch (IOException e) {
            log.warn("知识库检索失败（按无命中降级）: {}", e.toString());
            return List.of();
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private Query parseTextQuery(String query) {
        try {
            Map<String, Float> boosts = Map.of(F_TITLE, 2.0f, F_HEADING, 1.5f);
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{F_TITLE, F_HEADING, F_CONTENT, F_TAG}, analyzer, boosts);
            parser.setDefaultOperator(QueryParser.Operator.OR);
            parser.setAllowLeadingWildcard(false);
            return parser.parse(QueryParser.escape(query));
        } catch (ParseException e) {
            log.debug("知识库查询解析失败（按无命中）: q={} err={}", query, e.getMessage());
            return null;
        }
    }

    private Query filterQuery(Filter filter, boolean withMatchAll) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        if (withMatchAll) {
            builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        }
        if (filter != null) {
            if (filter.kbIds() != null && !filter.kbIds().isEmpty()) {
                builder.add(new TermInSetQuery(F_KB_ID, refs(filter.kbIds())), BooleanClause.Occur.FILTER);
            }
            if (filter.docTypes() != null && !filter.docTypes().isEmpty()) {
                List<BytesRef> refs = filter.docTypes().stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(t -> new BytesRef(t.toUpperCase(Locale.ROOT)))
                        .toList();
                if (!refs.isEmpty()) {
                    builder.add(new TermInSetQuery(F_DOC_TYPE, refs), BooleanClause.Occur.FILTER);
                }
            }
            if (filter.tags() != null && !filter.tags().isEmpty()) {
                List<BytesRef> tags = filter.tags().stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(t -> new BytesRef(t.toLowerCase(Locale.ROOT)))
                        .distinct()
                        .toList();
                if (!tags.isEmpty()) {
                    builder.add(new TermInSetQuery(F_TAG, tags), BooleanClause.Occur.FILTER);
                }
            }
        }
        builder.add(new TermQuery(new Term(F_STATUS, "active")), BooleanClause.Occur.FILTER);
        long now = Instant.now().toEpochMilli();
        Query validTo = new BooleanQuery.Builder()
                .add(LongPoint.newExactQuery(F_VALID_TO, 0L), BooleanClause.Occur.SHOULD)
                .add(LongPoint.newRangeQuery(F_VALID_TO, now + 1, Long.MAX_VALUE), BooleanClause.Occur.SHOULD)
                .setMinimumNumberShouldMatch(1)
                .build();
        builder.add(validTo, BooleanClause.Occur.FILTER);
        builder.add(LongPoint.newRangeQuery(F_VALID_FROM, 0L, now), BooleanClause.Occur.FILTER);
        return builder.build();
    }

    private static List<BytesRef> refs(List<Long> ids) {
        return ids.stream().filter(java.util.Objects::nonNull)
                .map(id -> new BytesRef(Long.toString(id))).toList();
    }

    private void refreshVectorDims() {
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            vectorDims.clear();
            for (LeafReaderContext ctx : searcher.getIndexReader().leaves()) {
                for (FieldInfo fi : ctx.reader().getFieldInfos()) {
                    if (fi.name.startsWith("vec_")) {
                        try {
                            vectorDims.add(Integer.parseInt(fi.name.substring(4)));
                        } catch (NumberFormatException ignored) {
                            // ignore unknown field
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("读取知识库索引向量维度失败: {}", e.toString());
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    @Override
    public Set<Integer> vectorDimensions() {
        return Set.copyOf(vectorDims);
    }

    @Override
    public long numDocs() {
        refreshIfPending();
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            return searcher.getIndexReader().numDocs();
        } catch (IOException e) {
            return 0;
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    @Override
    public IndexStats stats() {
        refreshIfPending();
        long size = 0L;
        if (indexPath != null && Files.exists(indexPath)) {
            try (var files = Files.walk(indexPath)) {
                size = files.filter(Files::isRegularFile).mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0L;
                    }
                }).sum();
            } catch (IOException ignored) {
                // 统计失败不阻塞
            }
        }
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            String version = searcher.getIndexReader() instanceof DirectoryReader dr
                    ? dr.getIndexCommit().getUserData().get("schemaVersion") : null;
            return new IndexStats(searcher.getIndexReader().numDocs(), size, version,
                    Set.copyOf(vectorDims));
        } catch (IOException e) {
            return new IndexStats(0L, size, null, Set.copyOf(vectorDims));
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static String vectorField(int dim) {
        return "vec_" + dim;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
