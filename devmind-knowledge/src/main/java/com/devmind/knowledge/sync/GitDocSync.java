package com.devmind.knowledge.sync;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.task.api.TaskContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.ingest.DocClassifier;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeSyncSourceEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CAP-85 FR-27：Git 文档目录增量同步。
 *
 * <p>只读服务器本地 git 副本（克隆/远端凭证由 CAP-29 负责），不接触网络与 PAT。
 * 首次全量、之后按 `<watermark>..<ref>` 的 name-status 增量；删除只标记 deprecated。
 * 所有 git 调用经参数数组启动子进程，固定超时与输出上限，不经 shell。</p>
 */
@Service
public class GitDocSync {

    private static final Logger log = LoggerFactory.getLogger(GitDocSync.class);
    private static final int OUTPUT_LIMIT = 8 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> DOC_TYPES = Set.of(
            KnowledgeEntryEntity.DOC_TECH, KnowledgeEntryEntity.DOC_MAINT,
            KnowledgeEntryEntity.DOC_FAQ, KnowledgeEntryEntity.DOC_ARTIFACT);

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeEntryRepository entryRepo;
    private final SyncedEntryWriter writer;
    private final KnowledgeProperties props;
    private final ObjectMapper mapper;

    public GitDocSync(KnowledgeBaseRepository kbRepo,
                      KnowledgeEntryRepository entryRepo,
                      SyncedEntryWriter writer,
                      KnowledgeProperties props,
                      ObjectMapper mapper) {
        this.kbRepo = kbRepo;
        this.entryRepo = entryRepo;
        this.writer = writer;
        this.props = props;
        this.mapper = mapper;
    }

    public SyncOutcome run(KnowledgeSyncSourceEntity source, TaskContext ctx) {
        GitConfig cfg = GitConfig.parse(source.getConfigJson(), mapper);
        KnowledgeBaseEntity kb = kbRepo.findById(source.getKbId())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "知识库不存在: " + source.getKbId()));
        Path repo = validateRepo(cfg.repoPath());
        String ref = validateRef(cfg.ref());
        String docsPath = normalizeDocsPath(cfg.docsPath());

        String sha = git(repo, "rev-parse", "--verify", ref + "^{commit}").trim();
        if (!sha.matches("[0-9a-fA-F]{7,64}")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "无法解析 git ref: " + ref);
        }
        String oldWatermark = source.getWatermark();
        boolean incremental = isAncestor(repo, oldWatermark, sha);
        if (ctx != null) {
            ctx.log("Git 同步 " + repo + " @" + ref.substring(0, Math.min(12, ref.length()))
                    + (incremental ? "（增量 " + shortSha(oldWatermark) + ".." + shortSha(sha) + "）" : "（全量）"));
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int deprecated = 0;
        int failed = 0;
        boolean cancelled = false;
        Set<String> seen = new HashSet<>();

        List<DiffItem> items = incremental
                ? diffItems(repo, oldWatermark, sha, docsPath)
                : fullItems(repo, sha, docsPath);
        for (DiffItem item : items) {
            if (ctx != null && ctx.isCancelled()) {
                cancelled = true;
                break;
            }
            if (!isMarkdown(item.path())) {
                continue;
            }
            String externalId = externalId(source.getId(), item.path());
            try {
                if ("D".equals(item.status())) {
                    if (deprecateIfOwned(kb.getId(), source.getId(), externalId, "git")) {
                        deprecated++;
                    }
                    continue;
                }
                // 全量扫描时 D 不会出现在列表里，靠收尾差集处理
                String content = git(repo, "show", sha + ":" + item.path());
                int max = props.getUpload().getMaxParsedChars();
                if (content.length() > max) {
                    content = content.substring(0, max);
                }
                List<String> tags = new ArrayList<>(DocClassifier.tags(item.path()));
                tags.addAll(cfg.tags());
                String docType = cfg.docType() != null
                        ? cfg.docType() : defaultDocType(item.path());
                var outcome = writer.upsert(kb, KnowledgeEntryEntity.SOURCE_GIT, source.getId(),
                        externalId, repo + "/" + item.path(), titleOf(item.path(), content),
                        content, sha, docType, tags, "git-sync");
                seen.add(externalId);
                switch (outcome) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Git 文档同步失败: source={} path={} err={}",
                        source.getId(), item.path(), e.toString());
                if (ctx != null) {
                    ctx.log("失败 " + item.path() + "：" + e.getMessage());
                }
            }
        }

        if (!incremental && !cancelled) {
            for (KnowledgeEntryRepository.SyncEntryRef refRow : entryRepo.findRefsBySyncSourceId(source.getId())) {
                if (refRow.getExternalId() != null && !seen.contains(refRow.getExternalId())) {
                    KnowledgeEntryEntity e = entryRepo.findById(refRow.getId()).orElse(null);
                    if (e != null && writer.deprecate(e, "git-sync")) {
                        deprecated++;
                    }
                }
            }
        }
        String watermark = cancelled ? oldWatermark : sha;
        if (ctx != null) {
            ctx.log("Git 同步完成：created=" + created + " updated=" + updated
                    + " unchanged=" + unchanged + " deprecated=" + deprecated + " failed=" + failed);
        }
        return new SyncOutcome(watermark, created, updated, unchanged, deprecated, failed);
    }

    // ---------------- git 调用 ----------------

    private Path validateRepo(String repoPath) {
        if (repoPath == null || repoPath.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "repoPath 必填");
        }
        Path p;
        try {
            p = Path.of(repoPath);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "repoPath 非法: " + repoPath);
        }
        if (!p.isAbsolute()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "repoPath 必须是服务器本地绝对路径");
        }
        try {
            if (!Files.isDirectory(p)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "repoPath 不存在或不是目录: " + repoPath);
            }
            Path real = p.toRealPath();
            Result probe = gitResult(real, "rev-parse", "--git-dir");
            if (probe.exit() != 0) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "repoPath 不是 git 仓库: " + repoPath);
            }
            return real;
        } catch (DevMindException e) {
            throw e;
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "repoPath 不可访问: " + repoPath);
        }
    }

    private static String validateRef(String ref) {
        String r = ref == null || ref.isBlank() ? "HEAD" : ref.strip();
        if (r.startsWith("-") || r.contains("..") || r.contains("\\")
                || !r.matches("[A-Za-z0-9][A-Za-z0-9._/@^~{}-]{0,127}")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "ref 非法: " + ref);
        }
        return r;
    }

    private static String normalizeDocsPath(String docsPath) {
        if (docsPath == null || docsPath.isBlank()) {
            return "";
        }
        String p = docsPath.replace('\\', '/').strip();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.isBlank()) {
            return "";
        }
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*") || p.startsWith("-")
                || p.contains(":") || p.contains("\n") || p.contains("\r")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "docsPath 必须是仓库内相对路径: " + docsPath);
        }
        for (String seg : p.split("/")) {
            if (seg.equals("..") || seg.isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "docsPath 不允许包含 ..: " + docsPath);
            }
        }
        return p;
    }

    private boolean isAncestor(Path repo, String oldWatermark, String sha) {
        if (oldWatermark == null || !oldWatermark.matches("[0-9a-fA-F]{7,64}")) {
            return false;
        }
        Result r = gitResult(repo, "merge-base", "--is-ancestor", oldWatermark, sha);
        return r.exit() == 0;
    }

    private List<DiffItem> fullItems(Path repo, String sha, String docsPath) {
        List<String> args = new ArrayList<>(List.of("ls-tree", "-r", "--name-only", "-z", sha));
        if (!docsPath.isBlank()) {
            args.add("--");
            args.add(docsPath);
        }
        String out = git(repo, args.toArray(String[]::new));
        List<DiffItem> items = new ArrayList<>();
        for (String path : out.split("\0")) {
            if (!path.isBlank()) {
                items.add(new DiffItem("A", path));
            }
        }
        return items;
    }

    private List<DiffItem> diffItems(Path repo, String oldWatermark, String sha, String docsPath) {
        List<String> args = new ArrayList<>(List.of("diff", "--name-status", "-z", oldWatermark, sha));
        if (!docsPath.isBlank()) {
            args.add("--");
            args.add(docsPath);
        }
        String out = git(repo, args.toArray(String[]::new));
        List<DiffItem> items = new ArrayList<>();
        String[] tokens = out.split("\0", -1);
        int i = 0;
        while (i < tokens.length) {
            String status = tokens[i++];
            if (status.isBlank()) {
                continue;
            }
            char kind = status.charAt(0);
            if (kind == 'R' || kind == 'C') {
                if (i + 1 >= tokens.length) {
                    break;
                }
                String oldPath = tokens[i++];
                String newPath = tokens[i++];
                if (kind == 'R') {
                    items.add(new DiffItem("D", oldPath));
                }
                items.add(new DiffItem("A", newPath));
            } else {
                if (i >= tokens.length) {
                    break;
                }
                items.add(new DiffItem(String.valueOf(kind), tokens[i++]));
            }
        }
        return items;
    }

    private String git(Path repo, String... args) {
        Result r = gitResult(repo, args);
        if (r.exit() != 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "git " + args[0] + " 失败（exit=" + r.exit() + "）：" + abbreviate(r.out()));
        }
        return r.out();
    }

    private Result gitResult(Path repo, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repo.toString());
        cmd.add("--no-optional-locks");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        try {
            Process p = pb.start();
            CompletableFuture<byte[]> outFuture = CompletableFuture.supplyAsync(() -> {
                try (InputStream in = p.getInputStream()) {
                    return readBounded(in, OUTPUT_LIMIT);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
            if (!p.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "git " + args[0] + " 超时（" + TIMEOUT.toSeconds() + "s）");
            }
            byte[] bytes = outFuture.get(5, TimeUnit.SECONDS);
            return new Result(p.exitValue(), new String(bytes, StandardCharsets.UTF_8));
        } catch (DevMindException e) {
            throw e;
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "git 执行失败: " + e.getMessage());
        }
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            if (total < max) {
                int w = Math.min(n, max - total);
                out.write(buf, 0, w);
                total += w;
            }
        }
        return out.toByteArray();
    }

    // ---------------- 条目字段 ----------------

    private boolean deprecateIfOwned(Long kbId, Long sourceId, String externalId, String actor) {
        return entryRepo.findByKbIdAndExternalId(kbId, externalId)
                .filter(e -> sourceId.equals(e.getSyncSourceId()))
                .map(e -> writer.deprecate(e, actor))
                .orElse(false);
    }

    private static String externalId(Long sourceId, String path) {
        return "sync:" + sourceId + ":" + path;
    }

    private static boolean isMarkdown(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private static String defaultDocType(String path) {
        String type = DocClassifier.classify(path);
        return type == null ? KnowledgeEntryEntity.DOC_TECH : type;
    }

    private static String titleOf(String path, String content) {
        for (String line : content.split("\\R", 40)) {
            String s = line.strip();
            if (s.startsWith("# ")) {
                String title = s.substring(2).strip();
                if (!title.isBlank()) {
                    return title.length() <= 200 ? title : title.substring(0, 200);
                }
            }
        }
        String file = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static String shortSha(String sha) {
        if (sha == null) {
            return "-";
        }
        return sha.length() <= 12 ? sha : sha.substring(0, 12);
    }

    private static String abbreviate(String s) {
        String oneLine = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }

    private record Result(int exit, String out) {
    }

    private record DiffItem(String status, String path) {
    }

    private record GitConfig(String repoPath, String ref, String docsPath, String docType,
                             List<String> tags) {
        static GitConfig parse(String json, ObjectMapper mapper) {
            JsonNode n;
            try {
                n = json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
            } catch (Exception e) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "同步源配置不是合法 JSON");
            }
            String docType = n.path("docType").asText("");
            if (!docType.isBlank()) {
                docType = docType.strip().toUpperCase(Locale.ROOT);
                if (!DOC_TYPES.contains(docType)) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "docType 必须是 TECH/MAINT/FAQ/ARTIFACT");
                }
            } else {
                docType = null;
            }
            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = n.path("tags");
            if (tagsNode.isArray()) {
                tagsNode.forEach(t -> {
                    if (t.asText("").isBlank() == false) {
                        tags.add(t.asText().strip());
                    }
                });
            } else if (tagsNode.isTextual() && !tagsNode.asText().isBlank()) {
                for (String t : tagsNode.asText().split(",")) {
                    if (!t.isBlank()) {
                        tags.add(t.strip());
                    }
                }
            }
            return new GitConfig(n.path("repoPath").asText(""), n.path("ref").asText("HEAD"),
                    n.path("docsPath").asText("docs"), docType,
                    List.copyOf(new LinkedHashSet<>(tags)));
        }
    }
}
