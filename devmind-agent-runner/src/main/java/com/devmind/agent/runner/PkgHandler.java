package com.devmind.agent.runner;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * CAP-57 pkg 帧 handler（协议 v15）：安装包分发——凭节点 token 走 HTTP 拉取
 * （{@code GET /api/agent/classify/packages/{id}?token=}，「节点拉取」模式照
 * {@link LabBundlePuller}/{@link RunnerUpgrader} 先例，不建服务端推送通道）。
 *
 * <p>流程：流式下载 + sha256 校验（复用 {@link RunnerUpgrader#downloadAndVerify}，GB 级
 * 不进内存）→ 解 zip 到 {@code <installDir>.tmp}（ZipFile 随机访问，非 byte[]）→
 * 原子 rename 到 installDir（已存在的旧版先挪 .old 再删，失败尽力回滚）→ 写
 * {@code <installDir>.sha256} 标记 → pkg_ack 带回节点侧绝对路径（服务端落
 * classify_package_installs.install_dir）。失败清理 .tmp/.download 不留半成品。</p>
 */
public class PkgHandler {

    private static final Logger log = LoggerFactory.getLogger(PkgHandler.class);

    private final RunnerConfig config;
    private final Path classifyRoot; // <workspaceRoot>/classify（绝对、归一化）
    private final Consumer<Map<String, Object>> sender;

    public PkgHandler(RunnerConfig config, Path workspaceRoot, Consumer<Map<String, Object>> sender) {
        this.config = config;
        this.classifyRoot = workspaceRoot.toAbsolutePath().normalize().resolve("classify");
        this.sender = sender;
    }

    /** 帧入口（WS listener 线程）——GB 级下载以分钟计，虚拟线程异步，ack 异步回 */
    public void handle(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        long packageId = frame.path("packageId").asLong(-1);
        String sha256 = frame.path("sha256").asText("");
        String fileName = frame.path("fileName").asText("");
        String installDirRel = frame.path("installDir").asText("");
        Thread.ofVirtual().name("pkg-install-" + packageId).start(() -> {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("type", "pkg_ack");
            ack.put("requestId", requestId);
            try {
                if (packageId <= 0) {
                    throw new IllegalStateException("非法 packageId: " + packageId);
                }
                if (!sha256.matches("[0-9a-fA-F]{64}")) {
                    throw new IllegalStateException("sha256 格式非法（期望 64 位 hex）: " + sha256);
                }
                Path installDir = contained(installDirRel);
                install(ack, packageId, sha256, fileName, installDir);
            } catch (Exception e) {
                log.warn("安装包分发失败: package={} err={}", packageId, e.getMessage());
                ack.put("ok", false);
                ack.put("error", String.valueOf(e.getMessage()));
            }
            sender.accept(ack);
        });
    }

    private void install(Map<String, Object> ack, long packageId, String sha256, String fileName,
                         Path installDir) throws Exception {
        String url = RunnerUpgrader.serverHttpBase(config)
                + "/api/agent/classify/packages/" + packageId
                + "?token=" + URLEncoder.encode(config.token(), StandardCharsets.UTF_8);
        Path download = installDir.resolveSibling(installDir.getFileName() + ".download");
        Path tmp = installDir.resolveSibling(installDir.getFileName() + ".tmp");
        Path old = installDir.resolveSibling(installDir.getFileName() + ".old");
        try {
            log.info("开始拉取安装包: package={} file={} dir={}", packageId, fileName, installDir);
            RunnerUpgrader.downloadAndVerify(url, sha256, download);
            deleteQuietly(tmp);
            extract(download, tmp);
            // 原子换版：旧版挪 .old → .tmp 上位 → 删 .old；上位失败尽力把旧版挪回来
            deleteQuietly(old);
            boolean swapped = false;
            if (Files.exists(installDir)) {
                Files.move(installDir, old);
                swapped = true;
            }
            try {
                Files.move(tmp, installDir);
            } catch (Exception e) {
                if (swapped) {
                    try {
                        Files.move(old, installDir);
                    } catch (Exception rollbackErr) {
                        log.error("安装目录回滚失败（旧版在 {}）: {}", old, rollbackErr.getMessage());
                    }
                }
                throw e;
            }
            deleteQuietly(old);
            Files.writeString(installDir.resolveSibling(installDir.getFileName() + ".sha256"),
                    sha256 + "  " + (fileName == null ? "" : fileName) + "\n", StandardCharsets.UTF_8);
            log.info("安装包就绪: package={} dir={}", packageId, installDir);
            ack.put("ok", true);
            ack.put("installDir", installDir.toAbsolutePath().toString());
        } finally {
            Files.deleteIfExists(download);
            deleteQuietly(tmp);
        }
    }

    /** 收容校验：installDir 必须相对 classify/ 根且归一化后不越界 */
    private Path contained(String rel) {
        if (rel == null || rel.isBlank()) {
            throw new IllegalStateException("installDir 为空（pkg 帧路径字段必填）");
        }
        Path p = Path.of(rel);
        if (p.isAbsolute()) {
            throw new IllegalStateException("installDir 必须是相对 classify/ 根的路径，收到绝对路径: " + rel);
        }
        Path resolved = classifyRoot.resolve(p).normalize();
        if (!resolved.startsWith(classifyRoot) || resolved.equals(classifyRoot)) {
            throw new IllegalStateException("installDir 越出收容根 classify/: " + rel);
        }
        return resolved;
    }

    /** 流式解 zip（ZipFile 随机访问，GB 级不进内存）；zip-slip 防护：条目归一化后须在目标目录内 */
    private static void extract(Path zip, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IllegalStateException("zip 条目越界（zip-slip）: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (var in = zf.getInputStream(entry)) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 递归删除（尽力而为，残留打日志；与 {@link LabBundlePuller#deleteQuietly} 同口径） */
    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            log.warn("临时目录清理失败（可手工删除）: {} {}", dir, e.getMessage());
        }
    }
}
