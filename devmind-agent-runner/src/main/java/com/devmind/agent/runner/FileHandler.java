package com.devmind.agent.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CAP-65 file 帧 handler（协议 v18，FR-02/FR-03）：节点文件浏览器——白名单根目录内的
 * list / read / write / rename / delete 五类小操作直接走 WS 帧，upload / download 大文件
 * 走 runner 发起的 HTTP 中转（{@code /api/agent/files-transfer/{id}?token=}，节点拉/推，
 * 照 {@link PkgHandler}/{@link OutputUploader} 先例不建服务端推送通道）。结果经
 * {@code file_ack} 回传。一律虚拟线程执行，不阻塞 WS listener 与心跳。
 *
 * <p><b>安全边界</b>：白名单由服务端 DB 权威下发（帧携带 roots 全量，runner 不读本机配置），
 * root 归一化后（\→/、去尾分隔符、Windows 盘符大小写不敏感）与白名单条目<b>精确匹配</b>；
 * path 一律相对 root，{@code resolve+normalize+startsWith} 防 {@code ..} 逃逸、
 * {@code toRealPath} 防符号链接逃逸（写入目标不存在时 realPath <b>父目录</b>）；
 * 深度 ≤{@link #MAX_DEPTH}。服务端已预检大小，runner 仍再校验（伪造帧防御）：
 * 文本 ≤{@link #TEXT_CAP_BYTES}、中转 ≤{@link #TRANSFER_CAP_BYTES}。</p>
 */
public class FileHandler {

    private static final Logger log = LoggerFactory.getLogger(FileHandler.class);

    static final int MAX_DEPTH = 32;
    static final int LIST_CAP = 1000;
    static final long TEXT_CAP_BYTES = 512L * 1024;
    static final long TRANSFER_CAP_BYTES = 100L * 1024 * 1024;
    private static final String GIT_DIR = ".git";

    private final RunnerConfig config;
    private final Consumer<Map<String, Object>> sender;

    public FileHandler(RunnerConfig config, Consumer<Map<String, Object>> sender) {
        this.config = config;
        this.sender = sender;
    }

    /** WS listener 线程入口：100MB 中转以分钟计，一律虚拟线程异步，ack 异步回。 */
    public void handle(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        Thread.ofVirtual().name("file-op-" + requestId).start(() -> run(frame));
    }

    private void run(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        String op = frame.path("op").asText("");
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "file_ack");
        ack.put("requestId", requestId);
        try {
            Path base = matchRoot(readRoots(frame), frame.path("root").asText(""));
            String rel = frame.path("path").asText("");
            Map<String, Object> payload = switch (op) {
                case "list" -> opList(base, rel);
                case "read" -> opRead(base, rel);
                case "write" -> opWrite(base, rel,
                        frame.hasNonNull("content") ? frame.path("content").asText() : null);
                case "rename" -> opRename(base, rel, frame.path("newName").asText(""));
                case "delete" -> opDelete(base, rel, frame.path("recursive").asBoolean(false));
                case "upload" -> opUpload(base, rel, frame);
                case "download" -> opDownload(base, rel, frame);
                default -> throw new IllegalStateException("未知 file op: " + op);
            };
            ack.put("ok", true);
            ack.put("payload", payload);
        } catch (Exception e) {
            log.warn("file 操作失败: op={} err={}", op, e.getMessage());
            ack.put("ok", false);
            ack.put("error", String.valueOf(e.getMessage()));
        }
        sender.accept(ack);
    }

    // ---------------- 白名单 ----------------

    private static List<String> readRoots(JsonNode frame) {
        List<String> roots = new ArrayList<>();
        JsonNode arr = frame.path("roots");
        if (arr.isArray()) {
            arr.forEach(n -> roots.add(n.asText("")));
        }
        return roots;
    }

    /**
     * root 归一化后与白名单条目精确匹配（服务端已判，runner 再判防伪造帧）。
     * 命中返回白名单条目原文对应的 Path（保留节点侧真实写法）。
     */
    static Path matchRoot(List<String> roots, String root) {
        if (roots.isEmpty()) {
            throw new IllegalStateException("节点未配置文件访问根目录白名单，文件操作不可用");
        }
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("缺少 root（须为白名单内的根目录）");
        }
        String want = normalizeRoot(root);
        for (String r : roots) {
            if (normalizeRoot(r).equals(want)) {
                return Path.of(r.trim());
            }
        }
        throw new IllegalStateException("root 不在节点文件访问白名单内: " + root);
    }

    /** 与服务端 AgentFileRoots.normalize 同口径：\→/、去尾分隔符（保留根级 "/"）、盘符小写。 */
    static String normalizeRoot(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() >= 2 && s.charAt(1) == ':') {
            s = s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
        }
        return s;
    }

    // ---------------- list / read ----------------

    /** 目录一层列表：目录优先按名称（忽略大小写）排序，跳过 .git，超 {@link #LIST_CAP} 截断。 */
    static Map<String, Object> opList(Path base, String rel) throws Exception {
        Path dir = WorkspaceQueryHandler.resolveConfined(base, rel, true, MAX_DEPTH);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("不是目录: " + (rel.isBlank() ? "/" : rel));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        try (var stream = Files.list(dir)) {
            List<Path> children = stream
                    .filter(p -> !GIT_DIR.equals(p.getFileName().toString()))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path p : children) {
                if (entries.size() >= LIST_CAP) {
                    truncated = true;
                    break;
                }
                Map<String, Object> e = new LinkedHashMap<>();
                boolean isDir = Files.isDirectory(p);
                e.put("name", p.getFileName().toString());
                e.put("dir", isDir);
                try {
                    e.put("mtime", Files.getLastModifiedTime(p).toInstant().toString());
                } catch (Exception ignored) {
                    // mtime 不可得就不带
                }
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

    /** 文本读取：≤{@link #TEXT_CAP_BYTES} 且前 8KB 无 NUL（二进制拒在线预览，引导下载）。 */
    static Map<String, Object> opRead(Path base, String rel) throws Exception {
        if (rel == null || rel.isBlank()) {
            throw new IllegalStateException("read 缺少 path");
        }
        Path p = WorkspaceQueryHandler.resolveConfined(base, rel, true, MAX_DEPTH);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("不是文件: " + rel);
        }
        long size = Files.size(p);
        if (size > TEXT_CAP_BYTES) {
            throw new IllegalStateException("文件过大（" + size + " 字节 > 512KB 上限），请下载查看");
        }
        byte[] bytes = Files.readAllBytes(p);
        int sniff = Math.min(bytes.length, 8192);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) {
                throw new IllegalStateException("二进制文件不支持在线预览，请下载查看: " + rel);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", new String(bytes, StandardCharsets.UTF_8));
        payload.put("size", size);
        return payload;
    }

    // ---------------- write / rename / delete ----------------

    /** UTF-8 文本原子写（同目录临时文件 + move）。目标父目录必须已存在。 */
    static Map<String, Object> opWrite(Path base, String rel, String content) throws Exception {
        if (content == null) {
            throw new IllegalStateException("write 缺少 content");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > TEXT_CAP_BYTES) {
            throw new IllegalStateException("文本保存超限（>512KB），请改用上传");
        }
        Path target = resolveForWrite(base, rel);
        if (Files.isDirectory(target)) {
            throw new IllegalStateException("目标是目录，不能写入: " + rel);
        }
        atomicWrite(target, bytes);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("size", (long) bytes.length);
        return payload;
    }

    /** 同目录改名：newName 不得含 / \ :，目标已存在拒绝（不静默覆盖）。 */
    static Map<String, Object> opRename(Path base, String rel, String newName) throws Exception {
        if (rel == null || rel.isBlank()) {
            throw new IllegalStateException("不能重命名根目录本身");
        }
        validateNewName(newName);
        Path src = WorkspaceQueryHandler.resolveConfined(base, rel, true, MAX_DEPTH);
        Path target = src.getParent().resolve(newName);
        Path realBase = base.toAbsolutePath().normalize().toRealPath();
        if (!target.startsWith(realBase)) {
            throw new IllegalStateException("路径越界（符号链接逃逸防护）: " + rel);
        }
        if (Files.exists(target)) {
            throw new IllegalStateException("目标已存在，拒绝覆盖: " + newName);
        }
        Files.move(src, target);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", newName);
        return payload;
    }

    static void validateNewName(String newName) {
        if (newName == null || newName.isBlank()
                || newName.contains("/") || newName.contains("\\") || newName.contains(":")
                || ".".equals(newName) || "..".equals(newName)) {
            throw new IllegalStateException("非法文件名（不得为空/含 / \\ :）: " + newName);
        }
    }

    /** 删除文件/空目录；非空目录须 recursive=true，且递归删除前重新解析校验（TOCTOU 防护）。 */
    static Map<String, Object> opDelete(Path base, String rel, boolean recursive) throws Exception {
        if (rel == null || rel.isBlank()) {
            throw new IllegalStateException("不能删除根目录本身");
        }
        Path target;
        try {
            target = WorkspaceQueryHandler.resolveConfined(base, rel, true, MAX_DEPTH);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new IllegalStateException("不存在: " + rel);
        }
        if (Files.isDirectory(target)) {
            if (!recursive) {
                try (var s = Files.list(target)) {
                    if (s.findAny().isPresent()) {
                        throw new IllegalStateException("目录非空，需 recursive=true 确认递归删除");
                    }
                }
                Files.delete(target);
            } else {
                // 递归前再校验一次：校验与删除之间目录可能被换成符号链接（walk 默认不跟随链接，链接本身按文件删）
                target = WorkspaceQueryHandler.resolveConfined(base, rel, true, MAX_DEPTH);
                try (var walk = Files.walk(target)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } else {
            Files.delete(target);
        }
        return Map.of();
    }

    // ---------------- upload / download（HTTP 中转） ----------------

    /** 上传：runner 主动 HTTP 拉中转 → sha256 校验 → 原子落位（伪造帧防御：大小再校验）。 */
    private Map<String, Object> opUpload(Path base, String rel, JsonNode frame) throws Exception {
        String transferId = frame.path("transferId").asText("");
        String sha256 = frame.path("sha256").asText("");
        long size = frame.path("size").asLong(-1);
        if (transferId.isBlank()) {
            throw new IllegalStateException("upload 缺少 transferId");
        }
        if (!sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("sha256 格式非法（期望 64 位 hex）: " + sha256);
        }
        if (size > TRANSFER_CAP_BYTES) {
            throw new IllegalStateException("文件超限（>100MB），拒绝上传");
        }
        Path target = resolveForWrite(base, rel);
        if (Files.isDirectory(target)) {
            throw new IllegalStateException("目标是目录，不能写入: " + rel);
        }
        String url = transferUrl(transferId);
        Path tmp = target.resolveSibling(target.getFileName() + ".devmind-dl");
        try {
            RunnerUpgrader.downloadAndVerify(url, sha256, tmp);
            moveAtomic(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("size", Files.size(target));
        return payload;
    }

    /** 下载：本地算 sha256 → runner 主动 HTTP 推中转（octet-stream + X-SHA256 头，服务端复核）。 */
    private Map<String, Object> opDownload(Path base, String rel, JsonNode frame) throws Exception {
        String transferId = frame.path("transferId").asText("");
        if (transferId.isBlank()) {
            throw new IllegalStateException("download 缺少 transferId");
        }
        if (rel == null || rel.isBlank()) {
            throw new IllegalStateException("download 缺少 path");
        }
        Path src = WorkspaceQueryHandler.resolveConfined(base, rel, true, MAX_DEPTH);
        if (!Files.isRegularFile(src)) {
            throw new IllegalStateException("不是文件: " + rel);
        }
        long size = Files.size(src);
        if (size > TRANSFER_CAP_BYTES) {
            throw new IllegalStateException("文件超限（" + size + " 字节 > 100MB 上限），不支持下载");
        }
        String sha256 = sha256Hex(src);
        HttpResponse<Void> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(transferUrl(transferId)))
                        .header("Content-Type", "application/octet-stream")
                        .header("X-SHA256", sha256)
                        .POST(HttpRequest.BodyPublishers.ofFile(src))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("中转推送失败: HTTP " + resp.statusCode());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("size", size);
        payload.put("sha256", sha256);
        return payload;
    }

    private String transferUrl(String transferId) {
        return RunnerUpgrader.serverHttpBase(config)
                + "/api/agent/files-transfer/" + URLEncoder.encode(transferId, StandardCharsets.UTF_8)
                + "?token=" + URLEncoder.encode(config.token(), StandardCharsets.UTF_8);
    }

    // ---------------- 公共 ----------------

    /**
     * 写入目标解析（目标可能不存在）：先 normalize+startsWith 防 {@code ..}，
     * 再 realPath <b>父目录</b>（必须已存在）防符号链接逃逸，最后拼回文件名。
     */
    static Path resolveForWrite(Path base, String rel) throws Exception {
        if (rel == null || rel.isBlank()) {
            throw new IllegalStateException("缺少 path");
        }
        Path p = WorkspaceQueryHandler.resolveConfined(base, rel, false, MAX_DEPTH);
        Path realBase = base.toAbsolutePath().normalize().toRealPath();
        Path parent = p.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalStateException("父目录不存在: " + rel);
        }
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(realBase)) {
            throw new IllegalStateException("路径越界（符号链接逃逸防护）: " + rel);
        }
        return realParent.resolve(p.getFileName().toString());
    }

    /** 原子落位：临时文件与目标同目录（同卷才能 ATOMIC_MOVE），不支持时退 REPLACE_EXISTING。 */
    static void atomicWrite(Path target, byte[] bytes) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".devmind-tmp");
        try {
            Files.write(tmp, bytes);
            moveAtomic(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static void moveAtomic(Path from, Path to) throws Exception {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file);
             var dis = new DigestInputStream(in, md)) {
            dis.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
