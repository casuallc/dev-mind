package com.devmind.serveradapter.http;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.serveradapter.spi.ConnectResult;
import com.devmind.serveradapter.spi.ExecResult;
import com.devmind.serveradapter.spi.HealthCheckConfig;
import com.devmind.serveradapter.spi.HealthResult;
import com.devmind.serveradapter.spi.ServerAdapter;
import com.devmind.serveradapter.spi.ServerTarget;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * CAP-07 FR-04 HTTP 实现（Server Agent）：目标机运行轻量 daemon，暴露 REST API。
 * 平台侧经 RestClient 调用，HTTPS 由部署方保证（MVP 支持 http/https），token 走 Bearer 鉴权。
 *
 * <p>协议（与 Server Agent 约定）：
 * <pre>
 *   GET  {base}/api/agent/ping                      → 200 pong
 *   POST {base}/api/agent/exec      {command}       → {exitCode, stdout, stderr}
 *   POST {base}/api/agent/health    {url?,command?} → {ok, message}
 *   POST {base}/api/agent/upload    multipart(file, remotePath) → {ok}
 *   GET  {base}/api/agent/download?path=…           → 文件文本
 * </pre>
 * 全部请求带 {@code Authorization: Bearer <token>}。
 */
@Component
public class HttpServerAdapter implements ServerAdapter {

    @Override
    public String supportedType() {
        return "http";
    }

    @Override
    public ConnectResult connectTest(ServerTarget target, long timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            String body = client(target, timeoutMs)
                    .get().uri("/api/agent/ping")
                    .retrieve().body(String.class);
            boolean ok = body != null && body.toLowerCase().contains("pong");
            return new ConnectResult(ok, ok ? "Server Agent 连通: " + base(target) : "异常响应: " + body,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new ConnectResult(false, "连接失败: " + rootMessage(e), System.currentTimeMillis() - start);
        }
    }

    @Override
    public ExecResult execute(ServerTarget target, String script, long timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            AgentExec r = client(target, timeoutMs)
                    .post().uri("/api/agent/exec")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("command", script == null ? "" : script))
                    .retrieve().body(AgentExec.class);
            if (r == null) {
                return new ExecResult(-1, false, "", "agent 无响应", System.currentTimeMillis() - start);
            }
            return new ExecResult(r.exitCode, r.exitCode == 0, r.stdout, r.stderr, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new ExecResult(-1, false, "", rootMessage(e), System.currentTimeMillis() - start);
        }
    }

    @Override
    public void upload(ServerTarget target, String localPath, String remotePath, long timeoutMs) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(localPath));
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    String name = Path.of(localPath).getFileName().toString();
                    return name;
                }
            });
            form.add("remotePath", remotePath);
            client(target, timeoutMs)
                    .post().uri("/api/agent/upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve().body(String.class);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "HTTP 上传失败: " + rootMessage(e));
        }
    }

    @Override
    public String download(ServerTarget target, String remotePath, long timeoutMs) {
        try {
            // 用 byte[] 读取并按 UTF-8 解码：agent 返回 text/plain 且无 charset，
            // StringHttpMessageConverter 会按 ISO-8859-1 解码导致中文乱码。
            byte[] bytes = client(target, timeoutMs)
                    .get().uri(uriBuilder -> uriBuilder.path("/api/agent/download").queryParam("path", remotePath).build())
                    .retrieve().body(byte[].class);
            return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "HTTP 下载失败: " + rootMessage(e));
        }
    }

    @Override
    public HealthResult healthCheck(ServerTarget target, HealthCheckConfig cfg, long timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            // Map.of 不允许 null 值：只放有值的字段（url 健康检查时 command 为空）
            Map<String, Object> bodyMap = new java.util.HashMap<>();
            if (cfg != null) {
                if (cfg.url() != null) {
                    bodyMap.put("url", cfg.url());
                }
                if (cfg.expectedStatus() != null) {
                    bodyMap.put("expectedStatus", cfg.expectedStatus());
                }
                if (cfg.command() != null) {
                    bodyMap.put("command", cfg.command());
                }
            }
            AgentHealth r = client(target, timeoutMs)
                    .post().uri("/api/agent/health")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyMap)
                    .retrieve().body(AgentHealth.class);
            if (r == null) {
                return new HealthResult(false, "agent 无响应", System.currentTimeMillis() - start);
            }
            return new HealthResult(r.ok, r.message, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new HealthResult(false, "健康检查失败: " + rootMessage(e), System.currentTimeMillis() - start);
        }
    }

    // ---------- 内部 ----------

    private RestClient client(ServerTarget target, long timeoutMs) {
        var rf = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeoutMs))
                        .build());
        rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder()
                .baseUrl(base(target))
                .requestFactory(rf)
                .defaultHeaders(h -> {
                    String token = target.str("token");
                    if (token != null && !token.isBlank()) {
                        h.setBearerAuth(token);
                    }
                })
                .build();
    }

    private String base(ServerTarget target) {
        String base = target.str("baseUrl", "");
        if (base.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "HTTP 服务器缺 baseUrl 配置");
        }
        return base;
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }

    /** agent /exec 响应 */
    public record AgentExec(int exitCode, String stdout, String stderr) {
    }

    /** agent /health 响应 */
    public record AgentHealth(boolean ok, String message) {
    }
}
