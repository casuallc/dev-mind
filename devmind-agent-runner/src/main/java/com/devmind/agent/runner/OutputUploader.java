package com.devmind.agent.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * CAP-37 FR-01 会话产出回传：会话进程退出后、finalizer 清理工作区之前，
 * 扫 {@code <sessionDir>/.devmind/output/}（CAP-14 输出契约目录，相对 claude cwd）
 * 同步 POST 到服务端 {@code /api/agent/output/{sessionId}}。服务端响应前已落库，
 * 保证 exit 帧到达时流程引擎读产出无竞态。
 *
 * <p>红线：任何失败都只返回错误信息，绝不抛出——exit 帧必须照常上行。
 * 老 runner 无本类不上传，服务端走降级通知（无回归）。</p>
 */
public final class OutputUploader {

    private static final Logger log = LoggerFactory.getLogger(OutputUploader.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** 与服务端 AgentOutputController 硬限制对齐 */
    private static final long MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_FILES = 16;
    private static final long MAX_TOTAL_BYTES = 4L * 1_048_576;
    /** 契约目录（同步于服务端 FlowOutputContract.OUTPUT_DIR，runner 不依赖 flow 模块） */
    private static final String OUTPUT_DIR = ".devmind/output";

    private OutputUploader() {
    }

    /**
     * 扫描并上传会话产出。目录不存在/为空 = 空操作返回 null。
     *
     * @return null = 成功或无产出；非 null = 失败原因（调用方 log + system 事件告知）
     */
    public static String upload(RunnerConfig config, String sessionId, Path sessionDir) {
        Path dir = sessionDir.resolve(OUTPUT_DIR);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("[A-Za-z0-9._-]{1,64}"))
                    .sorted()
                    .limit(MAX_FILES + 1L)
                    .toList();
        } catch (IOException e) {
            return "扫描产出目录失败: " + e.getMessage();
        }
        if (files.isEmpty()) {
            return null;
        }
        if (files.size() > MAX_FILES) {
            log.warn("产出文件数超限（>{}），只上传前 {} 个: session={}", MAX_FILES, MAX_FILES, sessionId);
            files = files.subList(0, MAX_FILES);
        }

        List<Map<String, String>> items = new ArrayList<>();
        long total = 0;
        for (Path f : files) {
            try {
                long size = Files.size(f);
                if (size > MAX_FILE_BYTES) {
                    log.warn("产出文件超限跳过: {} ({}B > {}B)", f.getFileName(), size, MAX_FILE_BYTES);
                    continue;
                }
                total += size;
                if (total > MAX_TOTAL_BYTES) {
                    log.warn("产出总量超限（>{}MB），其余跳过: session={}", MAX_TOTAL_BYTES / 1_048_576, sessionId);
                    break;
                }
                items.add(Map.of("name", f.getFileName().toString(),
                        "content", Files.readString(f, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                log.warn("读取产出文件失败跳过: {} err={}", f.getFileName(), e.getMessage());
            }
        }
        if (items.isEmpty()) {
            return null;
        }

        String url = RunnerUpgrader.serverHttpBase(config)
                + "/api/agent/output/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                + "?token=" + URLEncoder.encode(config.token(), StandardCharsets.UTF_8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", items);
        try {
            String json = JsonMapper.builder().build().writeValueAsString(body);
            HttpResponse<Void> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 200) {
                return "上传产出失败: HTTP " + resp.statusCode();
            }
            log.info("会话产出已回传: session={} files={}", sessionId,
                    items.stream().map(m -> m.get("name")).toList());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "上传产出被中断";
        } catch (Exception e) {
            return "上传产出失败: " + e.getMessage();
        }
    }
}
