package com.devmind.agent.runner;

import com.devmind.common.program.ProgramContract;
import com.devmind.common.program.ProgramManifest;
import com.devmind.common.program.ProgramPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * CAP-104 M1.5 程序包拉取：exec 帧 {@code bundle.kind=test-program} 时凭节点 token 拉
 * {@code /api/agent/test-program/assets/{id}/bundle}，校验 sha256 后<b>树形</b>解包到临时目录
 * （保留 lib/ 层级），再读取 {@code .devmind-program.json} 定位入口脚本。
 *
 * <p>上传侧已评审 + 服务端下发前复核 sha256，这里再校验一次响应头哈希：防止内网传输损坏
 * 或中间代理改写 ZIP。失败即整个 exec 失败，不降级跑空命令（同 CAP-34/56 口径）。</p>
 */
final class TestProgramPuller {

    private static final Logger log = LoggerFactory.getLogger(TestProgramPuller.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private TestProgramPuller() {
    }

    record Materialized(Path dir, Path root, ProgramManifest manifest, Path entry, String fileName) {
    }

    static Materialized pull(RunnerConfig config, String assetId) throws IOException {
        String url = RunnerUpgrader.serverHttpBase(config)
                + "/api/agent/test-program/assets/" + URLEncoder.encode(assetId, StandardCharsets.UTF_8)
                + "/bundle?token=" + URLEncoder.encode(config.token(), StandardCharsets.UTF_8);
        byte[] body;
        String fileName = "test-program-" + assetId + ".zip";
        String expectedSha;
        try {
            HttpResponse<byte[]> resp = RunnerHttp.newClient(config).send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new IOException("拉取程序包失败: HTTP " + resp.statusCode() + "（asset=" + assetId + "）");
            }
            body = resp.body();
            expectedSha = resp.headers().firstValue("X-Content-Sha256").orElse(null);
            String disposition = resp.headers().firstValue("Content-Disposition").orElse(null);
            String parsed = fileNameFrom(disposition);
            if (parsed != null) {
                fileName = parsed;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("拉取程序包被中断", e);
        }
        if (body == null || body.length == 0) {
            throw new IOException("程序包为空: asset=" + assetId);
        }
        String actualSha = sha256(body);
        if (expectedSha != null && !expectedSha.isBlank() && !expectedSha.equalsIgnoreCase(actualSha)) {
            throw new IOException("程序包 sha256 校验失败: expected=" + expectedSha + ", actual=" + actualSha);
        }

        Path dir = Files.createTempDirectory("devmind-program-");
        try {
            ProgramPackage.extractTree(body, dir);
            ProgramManifest manifest = ProgramManifest.readFromPackage(name -> {
                try {
                    Path p = dir.resolve(name).normalize();
                    return p.startsWith(dir) && Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
                } catch (IOException e) {
                    return null;
                }
            });
            Path entry = dir.resolve(manifest.entrypoint()).normalize();
            if (!entry.startsWith(dir) || !Files.isRegularFile(entry)) {
                throw new IOException("程序包缺入口脚本: " + manifest.entrypoint());
            }
            Files.createDirectories(dir.resolve(".devmind"));
            Files.createDirectories(dir.resolve(ProgramContract.ARTIFACTS_PATH));
            log.info("程序包物化完成: {} asset={} dir={} entry={}", fileName, assetId, dir, manifest.entrypoint());
            return new Materialized(dir, dir, manifest, entry, fileName);
        } catch (IOException | RuntimeException e) {
            LabBundlePuller.deleteQuietly(dir);
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String fileNameFrom(String disposition) {
        if (disposition == null) {
            return null;
        }
        int i = disposition.toLowerCase().indexOf("filename=");
        if (i < 0) {
            return null;
        }
        String name = disposition.substring(i + "filename=".length()).trim();
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
            name = name.substring(1, name.length() - 1);
        }
        return name.isBlank() ? null : name;
    }
}
