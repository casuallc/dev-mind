package com.devmind.agent.runner;

import com.devmind.common.util.GitCli;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CAP-54 workspace_query 帧 handler（runner 侧只读查询）：对会话代码目录执行
 * tree（目录一层）/ file（文件内容）/ diff（单文件 diff）/ status（git 快照）查询，
 * 结果经 {@code workspace_query_ack} 回传。虚拟线程异步执行，不阻塞 WS listener。
 *
 * <p><b>安全边界</b>（FR-05）：路径一律相对代码目录解析，{@code resolve+normalize+startsWith}
 * 防 {@code ..} 逃逸；tree/file 再经 {@code toRealPath} 防符号链接逃逸（diff 走 git pathspec，
 * 文件可能已删除不存在，只做路径形态校验）。tree 每目录上限 {@link #TREE_CAP} 条、
 * file 上限 {@link #FILE_CAP_BYTES}（二进制拒绝）、目录深度 ≤{@link #MAX_DEPTH}。</p>
 *
 * <p>会话定位走 {@link RunnerSessionRegistry#knownDirOf}（运行中优先，刚结束的走 recentDirs——
 * CAP-51 收口保留工作树，终态会话仍可浏览）。</p>
 */
public class WorkspaceQueryHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceQueryHandler.class);

    static final int TREE_CAP = 500;
    static final int FILE_CAP_BYTES = 256 * 1024;
    static final int MAX_DEPTH = 8;
    /** 目录列表里不展示的条目（.git 对用户纯噪音） */
    private static final String GIT_DIR = ".git";

    private final RunnerSessionRegistry sessions;
    private final GitStatusCollector collector;
    private final Consumer<Map<String, Object>> sender;

    public WorkspaceQueryHandler(RunnerSessionRegistry sessions, GitStatusCollector collector,
                                 Consumer<Map<String, Object>> sender) {
        this.sessions = sessions;
        this.collector = collector;
        this.sender = sender;
    }

    /** WS listener 线程入口：立即投虚拟线程。 */
    public void handle(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        Thread.ofVirtual().name("ws-query-" + requestId).start(() -> run(frame));
    }

    private void run(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        String sessionId = frame.path("sessionId").asText("");
        String action = frame.path("action").asText("");
        String repo = frame.path("repo").asText("");
        String path = frame.path("path").asText("");
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "workspace_query_ack");
        ack.put("requestId", requestId);
        try {
            Path base = sessions.knownDirOf(sessionId).orElseThrow(() ->
                    new IllegalStateException("会话不在本节点运行或工作区已释放（runner 重启后终态会话目录不可定位）"));
            Map<String, Object> payload = switch (action) {
                case "tree" -> tree(base, path);
                case "file" -> file(base, path);
                case "diff" -> diff(base, repo, path);
                case "status" -> collector.collect(base);
                default -> throw new IllegalStateException("未知 workspace_query action: " + action);
            };
            ack.put("ok", true);
            ack.put("payload", payload);
        } catch (Exception e) {
            log.debug("workspace_query 失败: action={} session={} err={}", action, sessionId, e.getMessage());
            ack.put("ok", false);
            ack.put("error", String.valueOf(e.getMessage()));
        }
        sender.accept(ack);
    }

    // ---------------- tree ----------------

    /** 目录一层列表：目录优先按名称排序，跳过 .git，超 {@link #TREE_CAP} 截断并置 truncated。 */
    static Map<String, Object> tree(Path base, String rel) throws Exception {
        Path dir = resolveConfined(base, rel, true);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("不是目录: " + (rel.isBlank() ? "/" : rel));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        try (var stream = Files.list(dir)) {
            List<Path> children = stream
                    .filter(p -> !GIT_DIR.equals(p.getFileName().toString()))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            for (Path p : children) {
                if (entries.size() >= TREE_CAP) {
                    truncated = true;
                    break;
                }
                Map<String, Object> e = new LinkedHashMap<>();
                boolean isDir = Files.isDirectory(p);
                e.put("name", p.getFileName().toString());
                e.put("path", relPath(base, p));
                e.put("dir", isDir);
                if (!isDir) {
                    try {
                        e.put("size", Files.size(p));
                    } catch (Exception ignored) {
                        // size 不可得就不带
                    }
                }
                entries.add(e);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entries", entries);
        payload.put("truncated", truncated);
        return payload;
    }

    // ---------------- file ----------------

    /** 文件内容：≤{@link #FILE_CAP_BYTES} 且文本（前 8KB 无 NUL）才返回。 */
    static Map<String, Object> file(Path base, String rel) throws Exception {
        if (rel.isBlank()) {
            throw new IllegalStateException("file 查询缺少 path");
        }
        Path p = resolveConfined(base, rel, true);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("不是文件: " + rel);
        }
        long size = Files.size(p);
        if (size > FILE_CAP_BYTES) {
            throw new IllegalStateException("文件过大（" + size + " 字节 > " + FILE_CAP_BYTES + " 上限），不支持在线查看");
        }
        byte[] bytes = Files.readAllBytes(p);
        int sniff = (int) Math.min(bytes.length, 8192);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) {
                throw new IllegalStateException("二进制文件不支持在线查看: " + rel);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", new String(bytes, StandardCharsets.UTF_8));
        payload.put("size", size);
        return payload;
    }

    // ---------------- diff ----------------

    /**
     * 单文件 diff：repo 定位库目录（空 = 代码目录本身为库；非空 = 聚合根下子目录），
     * {@code git diff HEAD -- path} 合并 staged+unstaged 相对 HEAD 的全貌。
     * 未跟踪文件（git 不认识该 pathspec）返回 {@code {untracked:true}}，前端退回文件内容查看。
     */
    static Map<String, Object> diff(Path base, String repo, String rel) throws Exception {
        if (rel.isBlank()) {
            throw new IllegalStateException("diff 查询缺少 path");
        }
        rejectBadGitPath(rel);
        Path repoDir = base;
        if (!repo.isBlank()) {
            rejectBadGitPath(repo);
            repoDir = base.resolve(repo).normalize();
            if (!repoDir.startsWith(base)) {
                throw new IllegalStateException("repo 越界: " + repo);
            }
        }
        if (!Files.exists(repoDir.resolve(".git"))) {
            throw new IllegalStateException("非 git 仓库目录，无 diff 可看");
        }
        GitCli.Result tracked = GitCli.run(repoDir, GitStatusCollector.GIT_TIMEOUT_SEC,
                "git", "ls-files", "--error-unmatch", "--", rel);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (tracked.exitCode() != 0) {
            payload.put("untracked", true);
            payload.put("diff", "");
            return payload;
        }
        GitCli.Result d = GitCli.run(repoDir, GitStatusCollector.GIT_TIMEOUT_SEC,
                "git", "diff", "HEAD", "--", rel);
        if (d.exitCode() != 0) {
            throw new IllegalStateException("git diff 失败: " + d.err().strip());
        }
        payload.put("untracked", false);
        payload.put("diff", d.out());
        return payload;
    }

    // ---------------- 公共 ----------------

    /**
     * 相对路径限定在 base 子树内：normalize + startsWith 防 {@code ..} 逃逸；
     * realPath=true 时再经 toRealPath 防符号链接逃逸（目标必须存在）；深度 ≤{@link #MAX_DEPTH}。
     */
    static Path resolveConfined(Path base, String rel, boolean realPath) throws Exception {
        Path baseNorm = base.toAbsolutePath().normalize();
        if (rel == null || rel.isBlank() || "/".equals(rel)) {
            return baseNorm;
        }
        String clean = rel.replace('\\', '/');
        if (clean.startsWith("/") || clean.length() > 1 && clean.charAt(1) == ':') {
            throw new IllegalStateException("只接受相对路径: " + rel);
        }
        long depth = clean.chars().filter(c -> c == '/').count() + 1;
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("路径深度超过上限 " + MAX_DEPTH + ": " + rel);
        }
        Path p = baseNorm.resolve(clean).normalize();
        if (!p.startsWith(baseNorm)) {
            throw new IllegalStateException("路径越界（.. 逃逸防护）: " + rel);
        }
        if (realPath) {
            Path realBase = baseNorm.toRealPath();
            Path realP = p.toRealPath(); // 不存在即抛 NoSuchFileException
            if (!realP.startsWith(realBase)) {
                throw new IllegalStateException("路径越界（符号链接逃逸防护）: " + rel);
            }
            return realP;
        }
        return p;
    }

    /** git pathspec 形态校验（diff 的文件可能已删，不做存在性/realpath 校验，只挡越界形态）。 */
    private static void rejectBadGitPath(String rel) {
        String clean = rel.replace('\\', '/');
        if (clean.startsWith("/") || clean.length() > 1 && clean.charAt(1) == ':'
                || Path.of(clean).normalize().startsWith("..")) {
            throw new IllegalStateException("非法路径: " + rel);
        }
    }

    /** 相对 base 的 POSIX 风格路径（前端 tree 键用）。 */
    private static String relPath(Path base, Path p) {
        return base.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
