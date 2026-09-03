package com.devmind.agent.runner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * FR-09 升级助手（静态工具，保持 AgentRunnerMain 苗条）：
 * 下载 URL 派生 / 当前 jar 定位 / 下载+sha256 校验 / spawn SelfUpdater。
 */
public final class RunnerUpgrader {

    private RunnerUpgrader() {
    }

    /** 由 serverUrl（ws://host:8080/ws/agent）派生下载 URL：换 scheme + 换 path + 拼 token。 */
    public static String downloadUrl(RunnerConfig config) {
        URI uri = URI.create(config.serverUrl());
        String scheme = switch (uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "wss" -> "https";
            default -> "http";
        };
        String base = scheme + "://" + uri.getAuthority();
        return base + "/api/agent-nodes/runner-package/download?token="
                + URLEncoder.encode(config.token(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 当前运行 jar 路径（CodeSource）；IDE/classes 目录启动返回 empty（无法自升级）。 */
    public static Optional<Path> currentJar() {
        try {
            Path p = Path.of(RunnerUpgrader.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return Optional.of(p);
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 从 java.home 推导 java 可执行文件（跨平台，不依赖 PATH/JAVA_HOME）。 */
    public static String javaBin() {
        Path bin = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java");
        return bin.toString();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 同步下载到 newFile 并校验 sha256（不匹配删除残渣并抛异常）。 */
    public static void downloadAndVerify(String url, String sha256, Path newFile) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<InputStream> resp = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("下载失败: HTTP " + resp.statusCode());
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = resp.body()) {
            Files.createDirectories(newFile.toAbsolutePath().getParent());
            try (var out = Files.newOutputStream(newFile)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                }
            }
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!actual.equalsIgnoreCase(sha256 == null ? "" : sha256.strip())) {
            Files.deleteIfExists(newFile);
            throw new IOException("sha256 校验不匹配: 期望 " + sha256 + " 实际 " + actual);
        }
    }

    /**
     * spawn SelfUpdater 独立进程。注意 Windows 会锁定「有类被加载的 classpath jar」，
     * 若直接 -cp newJar，后续 move(newJar→target) 会失败——故先把新包复制成
     * <target>.updater.jar 作为 SelfUpdater 的 classpath（该副本常驻不删，下次升级覆盖）。
     */
    public static Process spawnSelfUpdater(Path target, Path newJar, Path configFile) throws IOException {
        Path updaterCp = target.resolveSibling(target.getFileName() + ".updater.jar");
        Files.copy(newJar, updaterCp, StandardCopyOption.REPLACE_EXISTING);
        return new ProcessBuilder(javaBin(), "-cp", updaterCp.toAbsolutePath().toString(),
                SelfUpdater.class.getName(),
                target.toAbsolutePath().toString(),
                newJar.toAbsolutePath().toString(),
                configFile.toAbsolutePath().toString())
                .directory(target.toAbsolutePath().getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        target.resolveSibling("runner-update.log").toFile()))
                .start();
    }

    /** 供 SelfUpdater 复用：原子替换（先 target→.bak，再 new→target），不支持 ATOMIC_MOVE 时降级。 */
    static void replace(Path target, Path newJar) throws IOException {
        Path bak = target.resolveSibling(target.getFileName() + ".bak");
        move(target, bak);
        move(newJar, target);
    }

    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
