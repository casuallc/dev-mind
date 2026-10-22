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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
 * 扫 {@code <sessionDir>/.devmind/output/}（CAP-14 输出契约目录；CAP-53 起相对代码目录=需求工作树，
 * claude cwd 已上抬到项目+用户根）
 * 同步 POST 到服务端 {@code /api/agent/output/{sessionId}}。服务端响应前已落库，
 * 保证 exit 帧到达时流程引擎读产出无竞态。
 *
 * <p>CAP-83 两路回传：严格 UTF-8 解码成功 = 文本产出，走既有 JSON 通道（CAP-37 不变）；
 * 解码失败 = 二进制产出（xlsx 等），逐文件走独立裸字节通道
 * {@code POST /api/agent/output/{sessionId}/attachment} 进服务端附件管理（CAP-32）。</p>
 *
 * <p>红线：任何失败都只返回错误信息，绝不抛出——exit 帧必须照常上行。
 * 老 runner 无附件通道不上传二进制，服务端走降级通知（无回归）。</p>
 */
public final class OutputUploader {

    private static final Logger log = LoggerFactory.getLogger(OutputUploader.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** 附件通道超时（单文件可达 20MB，宽于文本通道） */
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(180);
    /** 与服务端 AgentOutputController 硬限制对齐（文本通道单文件） */
    private static final long MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_FILES = 16;
    private static final long MAX_TOTAL_BYTES = 4L * 1_048_576;
    /** CAP-83 附件通道单文件上限（与服务端附件默认 maxSizeMb=20MB 对齐；服务端以配置为准 400 兜底） */
    private static final long MAX_ATTACHMENT_BYTES = 20L * 1_048_576;
    /** CAP-83 附件通道单次回传文件数上限（防失控刷请求） */
    private static final int MAX_ATTACHMENTS = 16;
    /** 契约目录（同步于服务端 FlowOutputContract.OUTPUT_DIR，runner 不依赖 flow 模块） */
    private static final String OUTPUT_DIR = ".devmind/output";
    /** 文件名白名单（与服务端 AgentOutputController.NAME_PATTERN 对齐）：Unicode 字母/数字 + . _ -，防路径逃逸 */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("[\\p{L}\\p{N}._-]{1,64}");

    private OutputUploader() {
    }

    /** 产出文件名合法性（白名单内 = 可回传；中文文件名合法，路径分隔符/控制符非法） */
    static boolean isValidOutputName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * 扫描并上传会话产出。目录不存在/为空 = 空操作返回 null。
     *
     * @return null = 成功或无产出；非 null = 失败原因（调用方 log + system 事件告知）
     */
    public static String upload(RunnerConfig config, String sessionId, Path sessionDir) {
        return upload(config, sessionId, sessionDir, null);
    }

    /**
     * 扫描并上传会话产出（带归属过滤）。
     *
     * <p>CAP-74 修订（2026-10-19）：worklog 持久草稿区 {worklogRoot}/&lt;owner&gt;/ 跨会话共享
     * 且永不删除，不过滤会把历史会话攒下的旧稿全部扫进本会话回传（"一推好多天"的根因）。
     * {@code notBefore} 非空时只回传 mtime ≥ 该时刻的文件（= 本会话产出），旧稿原地保留、
     * 不再重复上行；误纳无碍（服务端镜像有同内容零写/手改保护两道闸兜底），漏纳才丢数据，
     * 故调用方传入的时刻应带宽限略早于真实启动点。</p>
     *
     * @param notBefore 只回传该时刻之后修改的文件；null = 不过滤（repo/chat 会话目录本会话独占，无需过滤）
     * @return null = 成功或无产出；非 null = 失败原因（文本/附件通道各自的失败聚合）
     */
    public static String upload(RunnerConfig config, String sessionId, Path sessionDir,
                                java.time.Instant notBefore) {
        Path dir = sessionDir.resolve(OUTPUT_DIR);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> {
                        if (!isValidOutputName(p.getFileName().toString())) {
                            log.warn("产出文件名不在白名单，跳过回传: {}", p.getFileName());
                            return false;
                        }
                        if (notBefore != null) {
                            try {
                                if (Files.getLastModifiedTime(p).toInstant().isBefore(notBefore)) {
                                    log.debug("产出文件早于本会话启动，视为历史遗留跳过: {}", p.getFileName());
                                    return false;
                                }
                            } catch (IOException e) {
                                log.warn("读产出文件 mtime 失败跳过: {} err={}", p.getFileName(), e.getMessage());
                                return false;
                            }
                        }
                        return true;
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return "扫描产出目录失败: " + e.getMessage();
        }
        if (files.isEmpty()) {
            return null;
        }

        // CAP-83 分类：严格 UTF-8 解码成功 = 文本（既有 JSON 通道，原上限口径不变）；
        // 解码失败 = 二进制附件（独立裸字节通道，单文件 ≤20MB）
        List<Map<String, String>> texts = new ArrayList<>();
        List<BinaryItem> binaries = new ArrayList<>();
        long textTotal = 0;
        boolean textOverflowWarned = false;
        for (Path f : files) {
            try {
                long size = Files.size(f);
                if (size > MAX_ATTACHMENT_BYTES) {
                    log.warn("产出文件超限跳过: {} ({}B > {}B)", f.getFileName(), size, MAX_ATTACHMENT_BYTES);
                    continue;
                }
                byte[] bytes = Files.readAllBytes(f);
                String text = tryDecodeUtf8(bytes);
                if (text == null) {
                    if (binaries.size() >= MAX_ATTACHMENTS) {
                        log.warn("二进制产出文件数超限（>{}），跳过: {}", MAX_ATTACHMENTS, f.getFileName());
                        continue;
                    }
                    binaries.add(new BinaryItem(f, bytes));
                    continue;
                }
                if (bytes.length > MAX_FILE_BYTES) {
                    log.warn("产出文件超限跳过: {} ({}B > {}B)", f.getFileName(), size, MAX_FILE_BYTES);
                    continue;
                }
                if (texts.size() >= MAX_FILES) {
                    log.warn("文本产出文件数超限（>{}），跳过: {}", MAX_FILES, f.getFileName());
                    continue;
                }
                textTotal += bytes.length;
                if (textTotal > MAX_TOTAL_BYTES) {
                    if (!textOverflowWarned) {
                        log.warn("文本产出总量超限（>{}MB），其余文本文件跳过: session={}",
                                MAX_TOTAL_BYTES / 1_048_576, sessionId);
                        textOverflowWarned = true;
                    }
                    continue;
                }
                texts.add(Map.of("name", f.getFileName().toString(), "content", text));
            } catch (IOException e) {
                log.warn("读取产出文件失败跳过: {} err={}", f.getFileName(), e.getMessage());
            }
        }

        List<String> errors = new ArrayList<>();
        if (!texts.isEmpty()) {
            String err = uploadTexts(config, sessionId, texts);
            if (err != null) {
                errors.add(err);
            }
        }
        for (BinaryItem b : binaries) {
            String err = uploadAttachment(config, sessionId, b);
            if (err != null) {
                errors.add(err);
            }
        }
        return errors.isEmpty() ? null : String.join("；", errors);
    }

    /** 严格 UTF-8 解码：成功返回文本（= 文本产出），失败返回 null（= 二进制产出，走附件通道）。 */
    private static String tryDecodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** 文本通道（CAP-37 原路径）：JSON POST /api/agent/output/{sessionId}。 */
    private static String uploadTexts(RunnerConfig config, String sessionId,
                                      List<Map<String, String>> items) {
        String url = RunnerUpgrader.serverHttpBase(config)
                + "/api/agent/output/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                + "?token=" + URLEncoder.encode(config.token(), StandardCharsets.UTF_8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", items);
        try {
            String json = JsonMapper.builder().build().writeValueAsString(body);
            HttpResponse<Void> resp = RunnerHttp.newClient(config).send(
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

    /** CAP-83 附件通道：裸字节 POST /api/agent/output/{sessionId}/attachment?name=&contentType=&token=。 */
    private static String uploadAttachment(RunnerConfig config, String sessionId, BinaryItem item) {
        String name = item.path().getFileName().toString();
        String contentType = null;
        try {
            contentType = Files.probeContentType(item.path());
        } catch (IOException e) {
            log.debug("探测产出文件 mime 失败（按 octet-stream 上传）: {} err={}", name, e.getMessage());
        }
        StringBuilder url = new StringBuilder(RunnerUpgrader.serverHttpBase(config))
                .append("/api/agent/output/").append(URLEncoder.encode(sessionId, StandardCharsets.UTF_8))
                .append("/attachment?name=").append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append("&token=").append(URLEncoder.encode(config.token(), StandardCharsets.UTF_8));
        if (contentType != null && !contentType.isBlank()) {
            url.append("&contentType=").append(URLEncoder.encode(contentType, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<Void> resp = RunnerHttp.newClient(config).send(
                    HttpRequest.newBuilder(URI.create(url.toString())).timeout(ATTACH_TIMEOUT)
                            .header("Content-Type", "application/octet-stream")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(item.bytes()))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 200) {
                return "上传二进制产出失败: " + name + " HTTP " + resp.statusCode();
            }
            log.info("二进制会话产出已回传: session={} file={} size={}", sessionId, name, item.bytes().length);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "上传二进制产出被中断: " + name;
        } catch (Exception e) {
            return "上传二进制产出失败: " + name + " " + e.getMessage();
        }
    }

    /** 已读入内存的二进制产出（分类阶段为判定文本/二进制已读全量字节，避免二次读盘）。 */
    private record BinaryItem(Path path, byte[] bytes) {
    }
}
