package com.devmind.agent.runner;

import com.devmind.common.agent.exec.ContextMaterializer;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextPackages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * CAP-34 FR-03 上下文包拉取（HTTP 通道先例 = RunnerUpgrader 下载升级包）：launch 帧带
 * contextManifest 时，凭节点 token 从服务端拉 {@link ContextPackage} 并物化到会话工作区。
 *
 * <p>红线：拉取/校验/物化任一失败抛异常 → 调用方回 {@code launched{ok:false}}，
 * 不静默降级为无上下文会话。</p>
 */
public final class ContextPuller {

    private static final Logger log = LoggerFactory.getLogger(ContextPuller.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private ContextPuller() {
    }

    /**
     * 拉包 → 校验（totalBytes/sha256 对照 manifest）→ 物化到 workDir。
     *
     * @param manifest launch 帧的 contextManifest 节点（entries/totalBytes/sha256）
     * @throws IOException 拉取或物化失败
     */
    public static void pullAndMaterialize(RunnerConfig config, String sessionId,
                                          JsonNode manifest, Path workDir) throws IOException {
        long expectedBytes = manifest.path("totalBytes").asLong(-1);
        String expectedSha = manifest.path("sha256").asText("");
        String url = RunnerUpgrader.serverHttpBase(config)
                + "/api/agent/context/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                + "?token=" + URLEncoder.encode(config.token(), StandardCharsets.UTF_8);

        byte[] body;
        try {
            HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new IOException("拉取上下文包失败: HTTP " + resp.statusCode());
            }
            body = resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("拉取上下文包被中断", e);
        }
        if (expectedBytes >= 0 && body.length != expectedBytes) {
            throw new IOException("上下文包大小不符: 期望 " + expectedBytes + " 实际 " + body.length);
        }
        String actualSha = ContextPackages.sha256Hex(body);
        if (!expectedSha.isBlank() && !expectedSha.equalsIgnoreCase(actualSha)) {
            throw new IOException("上下文包 sha256 不符: 期望 " + expectedSha + " 实际 " + actualSha);
        }

        ContextPackage pkg = ContextPackages.fromJson(body);
        ContextMaterializer.materialize(workDir, pkg);
        log.info("上下文包物化完成: session={} entries={} bytes={}",
                sessionId, manifest.path("entries").asInt(-1), body.length);
    }
}
