package com.devmind.knowledge.ingest;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.knowledge.KnowledgeVersionService;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.ClassifyPreviewItem;
import com.devmind.knowledge.dto.ImportBatchView;
import com.devmind.knowledge.dto.ImportSubmitResult;
import com.devmind.knowledge.enrich.KnowledgeEnrichService;
import com.devmind.knowledge.index.KnowledgeIndexService;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeImportBatchEntity;
import com.devmind.knowledge.model.KnowledgeImportFileEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.knowledge.repo.KnowledgeImportBatchRepository;
import com.devmind.knowledge.repo.KnowledgeImportFileRepository;
import com.devmind.task.api.TaskContext;
import com.devmind.task.api.TaskOptions;
import com.devmind.task.service.AsyncTaskService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * CAP-84 M1 本地上传摄入：文件/文件夹/ZIP → staging → knowledge.ingest 异步任务
 * → 解析/去重/版本 → 结构化分块 + Lucene 索引 → 逐文件状态回报。
 *
 * <p>任务方法不带事务：解析是 CPU/IO 长操作，条目保存与索引各自短事务；
 * 单文件失败不影响整批，重试按 contentHash 幂等。</p>
 */
@Service
public class LocalImportService {

    private static final Logger log = LoggerFactory.getLogger(LocalImportService.class);
    public static final String TASK_TYPE = "knowledge.ingest";
    private static final Set<String> ALLOWED = Set.of(
            "md", "markdown", "txt", "pdf", "docx", "pptx", "xlsx", "html", "htm", "zip");
    private static final Set<String> EXTRACTED_ALLOWED = Set.of(
            "md", "markdown", "txt", "pdf", "docx", "pptx", "xlsx", "html", "htm");
    private static final Set<String> DOC_TYPES = Set.of(
            KnowledgeEntryEntity.DOC_TECH, KnowledgeEntryEntity.DOC_MAINT,
            KnowledgeEntryEntity.DOC_FAQ, KnowledgeEntryEntity.DOC_ARTIFACT);

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeImportBatchRepository batchRepo;
    private final KnowledgeImportFileRepository fileRepo;
    private final FileParser parser;
    private final KnowledgeIndexService indexService;
    private final KnowledgeVersionService versionService;
    private final AsyncTaskService taskService;
    private final KnowledgeEnrichService enrichService;
    private final KnowledgeProperties props;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public LocalImportService(KnowledgeBaseRepository kbRepo,
                              KnowledgeEntryRepository entryRepo,
                              KnowledgeImportBatchRepository batchRepo,
                              KnowledgeImportFileRepository fileRepo,
                              FileParser parser,
                              KnowledgeIndexService indexService,
                              KnowledgeVersionService versionService,
                              @Lazy AsyncTaskService taskService,
                              KnowledgeEnrichService enrichService,
                              KnowledgeProperties props) {
        this.kbRepo = kbRepo;
        this.entryRepo = entryRepo;
        this.batchRepo = batchRepo;
        this.fileRepo = fileRepo;
        this.parser = parser;
        this.indexService = indexService;
        this.versionService = versionService;
        this.taskService = taskService;
        this.enrichService = enrichService;
        this.props = props;
    }

    public ImportSubmitResult submit(Long kbId, List<MultipartFile> uploads, List<String> relativePaths,
                                     String defaultDocType, List<String> tags, String actor) {
        KnowledgeBaseEntity kb = requireBase(kbId);
        if (KnowledgeBaseEntity.STATUS_ARCHIVED.equals(kb.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "已归档知识库不能导入文档");
        }
        if (uploads == null || uploads.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
        KnowledgeProperties.Upload cfg = props.getUpload();
        String batchId = UUID.randomUUID().toString().replace("-", "");
        Path staging = stagingRoot().resolve(batchId);
        try {
            Files.createDirectories(staging);
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "创建上传临时目录失败: " + e.getMessage());
        }

        String docType = normalizeDocType(defaultDocType);
        List<KnowledgeImportFileEntity> items = new ArrayList<>();
        long totalBytes = 0;
        long maxBytes = cfg.getMaxBatchMb() * 1024 * 1024;
        long maxFileBytes = cfg.getMaxFileMb() * 1024 * 1024;
        try {
            for (int i = 0; i < uploads.size(); i++) {
                MultipartFile upload = uploads.get(i);
                if (upload == null || upload.isEmpty()) {
                    continue;
                }
                String original = displayName(upload.getOriginalFilename(),
                        relativePaths != null && i < relativePaths.size() ? relativePaths.get(i) : null, i);
                String rel = safeRelative(relativePaths != null && i < relativePaths.size()
                        ? relativePaths.get(i) : original, original);
                String ext = extension(rel);
                if (!ALLOWED.contains(ext)) {
                    throw bad("不支持的文件类型: " + rel + "（允许 " + String.join("/", ALLOWED) + "）");
                }
                if (upload.getSize() > maxFileBytes) {
                    throw bad("单文件超过上限 " + cfg.getMaxFileMb() + "MB: " + rel);
                }
                totalBytes += upload.getSize();
                if (totalBytes > maxBytes) {
                    throw bad("单批上传超过上限 " + cfg.getMaxBatchMb() + "MB");
                }
                Path local = staging.resolve("upload-" + i + (ext.isEmpty() ? "" : "." + ext));
                upload.transferTo(local.toFile());
                if ("zip".equals(ext)) {
                    List<ZipGuard.Extracted> extracted = ZipGuard.extract(local, staging.resolve("zip-" + i),
                            cfg.getMaxBatchFiles() - items.size(), maxBytes - totalBytes, cfg.getMaxZipRatio(),
                            new ZipGuard.ImportLimits(maxFileBytes, EXTRACTED_ALLOWED));
                    String zipBase = stripExtension(fileName(rel));
                    for (ZipGuard.Extracted e : extracted) {
                        totalBytes += e.sizeBytes();
                        if (totalBytes > maxBytes) {
                            throw bad("ZIP 解压后单批总大小超过上限 " + cfg.getMaxBatchMb() + "MB");
                        }
                        String extractedRel = safeRelative(zipBase + "/" + e.relativePath(), e.relativePath());
                        items.add(newFile(batchId, extractedRel, e.path(), e.sizeBytes()));
                        if (items.size() >= cfg.getMaxBatchFiles()) {
                            throw bad("单批文件数超过上限 " + cfg.getMaxBatchFiles());
                        }
                    }
                    Files.deleteIfExists(local);
                } else {
                    items.add(newFile(batchId, rel, local, upload.getSize()));
                }
                if (items.size() > cfg.getMaxBatchFiles()) {
                    throw bad("单批文件数超过上限 " + cfg.getMaxBatchFiles());
                }
            }
        } catch (DevMindException e) {
            deleteQuietly(staging);
            throw e;
        } catch (Exception e) {
            deleteQuietly(staging);
            throw new DevMindException(ErrorCode.INTERNAL, "保存上传文件失败: " + e.getMessage());
        }
        if (items.isEmpty()) {
            deleteQuietly(staging);
            throw new DevMindException(ErrorCode.BAD_REQUEST, "没有可导入的文件");
        }

        KnowledgeImportBatchEntity batch = new KnowledgeImportBatchEntity();
        batch.setId(batchId);
        batch.setKbId(kbId);
        batch.setName(items.size() == 1 ? items.get(0).getFileName() : "上传 " + items.size() + " 个文件");
        batch.setDefaultDocType(docType);
        batch.setTags(joinTags(tags));
        batch.setStatus(KnowledgeImportBatchEntity.STATUS_RUNNING);
        batch.setTotalFiles(items.size());
        batch.setCreatedBy(actor);
        Instant now = Instant.now();
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchRepo.save(batch);
        items.forEach(fileRepo::save);

        String taskId = submitTask(batch, kb.getName());
        batch.setTaskId(taskId);
        batchRepo.save(batch);
        log.info("知识导入已受理: batch={} kb={} files={} task={}", batchId, kbId, items.size(), taskId);
        return new ImportSubmitResult(batchId, taskId, items.size());
    }

    /** FR-15 上传前分类预览：与入库同一套目录规则，避免前端另写一份规则漂移。 */
    public List<ClassifyPreviewItem> previewClassification(List<String> relativePaths, String defaultDocType) {
        String fallback = normalizeDocType(defaultDocType);
        List<ClassifyPreviewItem> items = new ArrayList<>();
        if (relativePaths == null) {
            return items;
        }
        for (String rel : relativePaths) {
            if (rel == null || rel.isBlank()) {
                continue;
            }
            String inferred = DocClassifier.classify(rel);
            items.add(new ClassifyPreviewItem(rel, inferred == null ? fallback : inferred,
                    inferred != null, DocClassifier.tags(rel)));
        }
        return items;
    }

    public ImportBatchView retry(String batchId) {        KnowledgeImportBatchEntity batch = requireBatch(batchId);
        if (running.contains(batchId)) {
            throw new DevMindException(ErrorCode.CONFLICT, "该批次正在处理中");
        }
        List<KnowledgeImportFileEntity> failed = fileRepo.findByBatchIdAndStatus(
                batchId, KnowledgeImportFileEntity.STATUS_FAILED);
        if (failed.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT, "该批次没有可重试的失败文件");
        }
        for (KnowledgeImportFileEntity f : failed) {
            f.setStatus(KnowledgeImportFileEntity.STATUS_PENDING);
            f.setError(null);
            fileRepo.save(f);
        }
        batch.setStatus(KnowledgeImportBatchEntity.STATUS_RUNNING);
        batch.setError(null);
        batch.setUpdatedAt(Instant.now());
        batch.setTaskId(submitTask(batch, "重试"));
        batchRepo.save(batch);
        return view(batch);
    }

    public ImportBatchView retryFile(String batchId, Long fileId) {
        KnowledgeImportBatchEntity batch = requireBatch(batchId);
        if (running.contains(batchId)) {
            throw new DevMindException(ErrorCode.CONFLICT, "该批次正在处理中");
        }
        KnowledgeImportFileEntity file = fileRepo.findById(fileId)
                .filter(f -> batchId.equals(f.getBatchId()))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "批次内文件不存在: " + fileId));
        if (!KnowledgeImportFileEntity.STATUS_FAILED.equals(file.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "只有失败文件可以单独重试");
        }
        file.setStatus(KnowledgeImportFileEntity.STATUS_PENDING);
        file.setError(null);
        fileRepo.save(file);
        batch.setStatus(KnowledgeImportBatchEntity.STATUS_RUNNING);
        batch.setError(null);
        batch.setUpdatedAt(Instant.now());
        batch.setTaskId(submitTask(batch, "重试 " + file.getFileName()));
        batchRepo.save(batch);
        return view(batch);
    }

    public ImportBatchView getBatch(String batchId) {
        return view(requireBatch(batchId));
    }

    public List<ImportBatchView> list(Long kbId) {
        List<KnowledgeImportBatchEntity> batches = kbId == null
                ? batchRepo.findAllByOrderByCreatedAtDesc()
                : batchRepo.findByKbIdOrderByCreatedAtDesc(kbId);
        return batches.stream().map(this::view).toList();
    }

    /** 任务入口：处理 pending 文件；重试幂等（同 contentHash 记 skipped）。 */
    public void processBatch(String batchId, TaskContext ctx) {
        if (!running.add(batchId)) {
            ctx.log("批次 " + batchId + " 已在处理中，跳过重复执行");
            return;
        }
        try {
            KnowledgeImportBatchEntity batch = requireBatch(batchId);
            batch.setStatus(KnowledgeImportBatchEntity.STATUS_RUNNING);
            batch.setError(null);
            batch.setUpdatedAt(Instant.now());
            batchRepo.save(batch);
            List<KnowledgeImportFileEntity> files = fileRepo.findByBatchIdOrderByRelativePathAsc(batchId);
            int total = files.size();
            int done = 0;
            for (KnowledgeImportFileEntity file : files) {
                if (KnowledgeImportFileEntity.STATUS_INDEXED.equals(file.getStatus())
                        || KnowledgeImportFileEntity.STATUS_SKIPPED.equals(file.getStatus())) {
                    done++;
                    continue;
                }
                if (ctx.isCancelled()) {
                    batch.setStatus(KnowledgeImportBatchEntity.STATUS_CANCELLED);
                    batch.setUpdatedAt(Instant.now());
                    batchRepo.save(batch);
                    ctx.log("批次已取消，已完成 " + done + "/" + total);
                    return;
                }
                processFile(batch, file, ctx);
                done++;
                ctx.progress(done + "/" + total);
                refreshCounts(batch);
            }
            finishBatch(batch);
            indexService.flush();
            ctx.log("知识导入完成: " + batch.getStatus() + " 成功 " + batch.getSuccessCount()
                    + " 跳过 " + batch.getSkippedCount() + " 失败 " + batch.getFailedCount());
            queueEnrich(batch, ctx);
        } finally {
            running.remove(batchId);
        }
    }

    /** CAP-85 FR-28：开启自动增强时，导入结束后排队一批（失败不影响导入结果）。 */
    private void queueEnrich(KnowledgeImportBatchEntity batch, TaskContext ctx) {
        if (!enrichService.autoEnabled() || batch.getSuccessCount() <= 0) {
            return;
        }
        try {
            String taskId = enrichService.submit(batch.getKbId(), null).taskId();
            ctx.log("已排队自动增强任务: " + taskId);
        } catch (Exception e) {
            log.warn("自动增强排队失败（不阻断导入）: batch={} err={}", batch.getId(), e.toString());
        }
    }

    private void processFile(KnowledgeImportBatchEntity batch, KnowledgeImportFileEntity file, TaskContext ctx) {
        file.setStatus(KnowledgeImportFileEntity.STATUS_PARSING);
        file.setError(null);
        fileRepo.save(file);
        try {
            Path source = Paths.get(file.getStoredPath());
            if (!Files.exists(source)) {
                throw new FileParseException("FILE_MISSING", "原始文件不存在，请重新上传");
            }
            StoredFile stored = persist(source, batch.getKbId(), file.getFileName());
            file.setStoredPath(stored.path());
            String content = parser.parse(Paths.get(stored.path()), file.getFileName());
            file.setMimeType(parser.detectMime(Paths.get(stored.path()), file.getFileName()));
            String hash = sha256(content);
            file.setContentHash(hash);

            KnowledgeEntryEntity existing = entryRepo
                    .findByKbIdAndSourceUri(batch.getKbId(), file.getRelativePath()).orElse(null);
            if (existing != null && hash.equals(existing.getContentHash())) {
                file.setEntryId(existing.getId());
                file.setStatus(KnowledgeImportFileEntity.STATUS_SKIPPED);
                fileRepo.save(file);
                ctx.log("跳过未变化: " + file.getRelativePath());
                return;
            }
            KnowledgeEntryEntity entry = existing == null ? new KnowledgeEntryEntity() : existing;
            boolean isNew = existing == null;
            applyEntry(entry, batch, file, content, hash, isNew);
            entry.setContentVersion(isNew ? 1 : entry.getContentVersion() + 1);
            entry = entryRepo.save(entry);
            versionService.record(entry, batch.getCreatedBy(), isNew ? "本地导入" : "本地导入（内容更新）");
            indexService.indexEntry(entry.getId());
            KnowledgeEntryEntity indexed = entryRepo.findById(entry.getId()).orElse(entry);
            file.setEntryId(entry.getId());
            if (KnowledgeEntryEntity.INDEX_FAILED.equals(indexed.getIndexStatus())) {
                file.setStatus(KnowledgeImportFileEntity.STATUS_FAILED);
                file.setError("索引失败: " + indexed.getIndexError());
                ctx.log("索引失败: " + file.getRelativePath() + " - " + indexed.getIndexError());
            } else {
                file.setStatus(KnowledgeImportFileEntity.STATUS_INDEXED);
                ctx.log("已导入: " + file.getRelativePath());
            }
            fileRepo.save(file);
        } catch (FileParseException e) {
            fail(file, e.getCode() + ": " + e.getMessage(), ctx);
        } catch (Exception e) {
            fail(file, e.getMessage(), ctx);
            log.warn("知识导入单文件失败: batch={} file={} err={}", batch.getId(), file.getRelativePath(), e.toString());
        }
    }

    private void applyEntry(KnowledgeEntryEntity entry, KnowledgeImportBatchEntity batch,
                            KnowledgeImportFileEntity file, String content, String hash, boolean isNew) {
        KnowledgeBaseEntity kb = requireBase(batch.getKbId());
        entry.setKbId(kb.getId());
        entry.setScope(kb.getScope());
        entry.setProjectId(kb.getProjectId());
        entry.setName(stripExtension(file.getFileName()));
        entry.setPath(truncate(file.getRelativePath(), 255));
        entry.setContentMd(content);
        entry.setContentHash(hash);
        entry.setSimHash(null);
        entry.setEnrichedAt(null);
        entry.setEnrichError(null);
        entry.setSource(KnowledgeEntryEntity.SOURCE_FILE);
        entry.setSourceUri(file.getRelativePath());
        entry.setMimeType(file.getMimeType());
        entry.setSummary(summary(content));
        entry.setOwner(batch.getCreatedBy());
        if (isNew) {
            entry.setStatus("active");
            entry.setHitCount(0);
            entry.setCreatedAt(Instant.now());
            entry.setContentVersion(1);
        }
        entry.setDocType(resolveDocType(file.getRelativePath(), batch.getDefaultDocType()));
        entry.setTags(joinTags(mergeTags(DocClassifier.tags(file.getRelativePath()), batch.getTags())));
        entry.setMetadataJson("{\"batchId\":\"" + batch.getId() + "\"}");
        entry.setUpdatedAt(Instant.now());
    }

    private void fail(KnowledgeImportFileEntity file, String message, TaskContext ctx) {
        file.setStatus(KnowledgeImportFileEntity.STATUS_FAILED);
        file.setError(truncate(message == null ? "未知错误" : message, 1000));
        fileRepo.save(file);
        ctx.log("导入失败: " + file.getRelativePath() + " - " + file.getError());
    }

    private void finishBatch(KnowledgeImportBatchEntity batch) {
        refreshCounts(batch);
        batch.setStatus(batch.getFailedCount() == 0
                ? KnowledgeImportBatchEntity.STATUS_DONE
                : (batch.getFailedCount() == batch.getTotalFiles()
                        ? KnowledgeImportBatchEntity.STATUS_FAILED
                        : KnowledgeImportBatchEntity.STATUS_PARTIAL));
        batch.setUpdatedAt(Instant.now());
        batchRepo.save(batch);
        if (batch.getFailedCount() == 0) {
            deleteQuietly(stagingRoot().resolve(batch.getId()));
        }
    }

    private void refreshCounts(KnowledgeImportBatchEntity batch) {
        long indexed = fileRepo.countByBatchIdAndStatus(batch.getId(), KnowledgeImportFileEntity.STATUS_INDEXED);
        long skipped = fileRepo.countByBatchIdAndStatus(batch.getId(), KnowledgeImportFileEntity.STATUS_SKIPPED);
        long failed = fileRepo.countByBatchIdAndStatus(batch.getId(), KnowledgeImportFileEntity.STATUS_FAILED);
        batch.setSuccessCount((int) indexed);
        batch.setSkippedCount((int) skipped);
        batch.setFailedCount((int) failed);
        batch.setUpdatedAt(Instant.now());
        batchRepo.save(batch);
    }

    private String submitTask(KnowledgeImportBatchEntity batch, String label) {
        String payload = "{\"batchId\":\"" + batch.getId() + "\"}";
        TaskOptions options = TaskOptions.builder()
                .name("知识导入 " + label)
                .retryMaxAttempts(2)
                .retryBackoff("FIXED")
                .retryIntervalMs(5_000)
                .timeoutMs(2 * 60 * 60_000L)
                .build();
        return taskService.submit(TASK_TYPE, payload, options);
    }

    private KnowledgeImportFileEntity newFile(String batchId, String relativePath, Path stored, long size) {
        KnowledgeImportFileEntity f = new KnowledgeImportFileEntity();
        f.setBatchId(batchId);
        f.setRelativePath(truncate(relativePath, 1024));
        f.setStoredPath(stored.toAbsolutePath().toString());
        f.setFileName(fileName(relativePath));
        f.setSizeBytes(size);
        f.setStatus(KnowledgeImportFileEntity.STATUS_PENDING);
        f.setCreatedAt(Instant.now());
        return f;
    }

    private KnowledgeImportBatchEntity requireBatch(String batchId) {
        return batchRepo.findById(batchId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "导入批次不存在: " + batchId));
    }

    private KnowledgeBaseEntity requireBase(Long kbId) {
        return kbRepo.findById(kbId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "知识库不存在: " + kbId));
    }

    private ImportBatchView view(KnowledgeImportBatchEntity batch) {
        List<ImportBatchView.File> files = fileRepo.findByBatchIdOrderByRelativePathAsc(batch.getId()).stream()
                .map(f -> new ImportBatchView.File(f.getId(), f.getRelativePath(), f.getFileName(),
                        f.getMimeType(), f.getSizeBytes(), f.getStatus(), f.getError(), f.getEntryId()))
                .toList();
        return view(batch, files);
    }

    private ImportBatchView view(KnowledgeImportBatchEntity batch, List<ImportBatchView.File> files) {
        return new ImportBatchView(batch.getId(), batch.getKbId(), batch.getTaskId(), batch.getName(),
                batch.getDefaultDocType(), batch.getTags(), batch.getStatus(), batch.getTotalFiles(),
                batch.getSuccessCount(), batch.getFailedCount(), batch.getSkippedCount(), batch.getError(),
                batch.getCreatedBy(), batch.getCreatedAt(), batch.getUpdatedAt(), files);
    }

    private StoredFile persist(Path source, Long kbId, String fileName) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        String hash = sha256(bytes);
        String ext = extension(fileName);
        Path target = filesRoot().resolve(String.valueOf(kbId))
                .resolve(hash.substring(0, 2))
                .resolve(hash + (ext.isEmpty() ? "" : "." + ext));
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredFile(target.toAbsolutePath().toString(), hash);
    }

    private Path stagingRoot() {
        return Paths.get(props.getUpload().getStagingPath()).toAbsolutePath().normalize();
    }

    private Path filesRoot() {
        return Paths.get(props.getUpload().getFilesPath()).toAbsolutePath().normalize();
    }

    private String resolveDocType(String relativePath, String defaultDocType) {
        String classified = DocClassifier.classify(relativePath);
        return classified != null ? classified : normalizeDocType(defaultDocType);
    }

    private static List<String> mergeTags(List<String> derived, String joined) {
        LinkedHashSet<String> out = new LinkedHashSet<>(derived);
        if (joined != null && !joined.isBlank()) {
            for (String t : joined.split(",")) {
                String s = t.strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static String normalizeDocType(String docType) {
        if (docType == null || docType.isBlank()) {
            return KnowledgeEntryEntity.DOC_TECH;
        }
        String upper = docType.strip().toUpperCase(Locale.ROOT);
        if (!DOC_TYPES.contains(upper)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "docType 必须是 TECH/MAINT/FAQ/ARTIFACT");
        }
        return upper;
    }

    private static String safeRelative(String raw, String fallback) {
        String p = DocClassifier.normalizePath(raw);
        if (p.isBlank()) {
            p = DocClassifier.normalizePath(fallback);
        }
        if (p.isBlank() || p.startsWith("../") || p.contains("/../") || p.equals("..")
                || (p.length() >= 2 && p.charAt(1) == ':')) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "文件相对路径非法: " + raw);
        }
        return truncate(p, 1024);
    }

    private static String displayName(String original, String relative, int index) {
        if (original != null && !original.isBlank()) {
            return original;
        }
        return relative != null && !relative.isBlank() ? relative : "upload-" + index;
    }

    private static String fileName(String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String summary(String content) {
        String oneLine = content.replaceAll("\\s+", " ").strip();
        return truncate(oneLine, 300);
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        String joined = String.join(",", tags);
        return truncate(joined, 480);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响主流程
                }
            });
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    private static DevMindException bad(String message) {
        return new DevMindException(ErrorCode.BAD_REQUEST, message);
    }

    private record StoredFile(String path, String hash) {
    }
}
