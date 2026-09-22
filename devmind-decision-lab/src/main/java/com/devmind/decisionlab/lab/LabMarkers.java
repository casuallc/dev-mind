package com.devmind.decisionlab.lab;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-56 脚本 ↔ 服务端的数据通道：脚本往 stdout 打单行 marker，服务端从日志流里捞出来。
 *
 * <p><b>为什么只能这样</b>：exec 链路没有任何文件上行通道（{@code AgentExecResult} 只有退出码，
 * 产出靠 stdout 行流）。所以要回传结构化的东西（指标、逐题结果、权重指纹），唯一可靠的办法
 * 就是"在日志里放一行机器可读的载荷"。CAP-08 的 {@code artifact=} 行是同一手法的祖先。</p>
 *
 * <p><b>格式</b>：{@code <MARKER> <base64(gzip(utf8 json))>}。三个约束各自解决一个坑：
 * <ul>
 *   <li>base64——保证这一行里不含换行/空格，逐行读日志不会把载荷撕成两半；</li>
 *   <li>gzip——逐题明细可能上百 KB，日志帧与数据库都不该被无谓撑大；</li>
 *   <li>单行 + 前缀——服务端能<b>把这些行从人看的日志里剔掉</b>（base64 大块进日志没人看，
 *       但它确实在节点上真实打印过，剔掉的是"搬运"，不是"隐瞒"：报告会落库并在页面上呈现）。</li>
 * </ul>
 *
 * <p><b>解码失败不抛</b>：一个 JSON 打错字不该让整次运行变成"没跑过"。解不出来的 marker 行
 * 记一条 warn、按"没有这份数据"处理（上层会把它显示成 MISSING/MALFORMED 的可见状态），
 * 而人读的日志与退出码仍然完整。</p>
 *
 * <p>marker 名的 Python 侧对应实现在 {@code tools/laya-sidecar/lab/_rl_common.py}，
 * 两边的字符串是一份契约。</p>
 */
public final class LabMarkers {

    private static final Logger log = LoggerFactory.getLogger(LabMarkers.class);

    /** 评测/微调的最终报告（指标 + 基线 + 逐题 + 校准） */
    public static final String REPORT = "DEVMIND_REPORT";

    /** 逐题进度事件（运行中实时推到前端；不落库，最终报告里有全量） */
    public static final String ITEM = "DEVMIND_ITEM";

    /** 产物指纹（微调结束时打印：path/bytes/sha256） */
    public static final String FINGERPRINT = "DEVMIND_FINGERPRINT";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LabMarkers() {
    }

    /** 脚本侧用的编码（服务端测试与 Python 实现对齐时也用它） */
    public static String encode(String marker, Object payload) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
                gz.write(MAPPER.writeValueAsBytes(payload));
            }
            return marker + " " + Base64.getEncoder().encodeToString(raw.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("marker 编码失败: " + e.getMessage(), e);
        }
    }

    /** 解码一行 marker 载荷；不是这一行 / 解不出来返回 empty（已记日志） */
    public static Optional<Map<String, Object>> decode(String marker, String line) {
        if (line == null) {
            return Optional.empty();
        }
        String head = marker + " ";
        String trimmed = line.strip();
        if (!trimmed.startsWith(head)) {
            return Optional.empty();
        }
        String b64 = trimmed.substring(head.length()).strip();
        try {
            byte[] gz;
            try {
                gz = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                // 也可能是脚本按明文 JSON 打的（版本不一致）：报清楚，别让人以为是数据错
                throw new IOException("marker 载荷不是 base64（脚本版本可能与平台不一致）", e);
            }
            byte[] json;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                json = in.readAllBytes();
            }
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                throw new IOException("marker 载荷不是 JSON 对象");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            node.properties().forEach(e -> out.put(e.getKey(), plain(e.getValue())));
            return Optional.of(out);
        } catch (IOException | RuntimeException e) {
            log.warn("marker 解析失败（按无数据继续）: marker={} 原因={}", marker, e.toString());
            return Optional.empty();
        }
    }

    /** JsonNode → 普通 Map/List/String/数值（落库与 WS 都要普通对象；Jackson 3 的 properties()） */
    private static Object plain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            node.properties().forEach(e -> m.put(e.getKey(), plain(e.getValue())));
            return m;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(plain(n)));
            return list;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }

    /**
     * 日志分流器：包一层原始 sink（推 ExecutionLogHub 的那个），做三件事——
     * <ol>
     *   <li>人读的行原样转发，同时攒一份（落库用）；</li>
     *   <li>marker 行<b>不转发也不落库</b>（base64 大块混在日志里没人看，报告另有落点）；</li>
     *   <li>REPORT/ITEM/FINGERPRINT 分别收进各自的口袋，供上层取用。</li>
     * </ol>
     *
     * <p>逐题事件是<b>流式</b>的：收到即回调，前端能看着它一题一题往下走；报告与指纹是小数据、
     * 只在结束前打印一次，留到最后取。</p>
     */
    public static final class Tap implements Consumer<String> {

        private static final int MAX_LOG_CHARS = 4_000_000;

        private final Consumer<String> downstream;
        private final Consumer<Map<String, Object>> onItem;
        private final StringBuilder logs = new StringBuilder();
        private Map<String, Object> report;
        private Map<String, Object> fingerprint;
        private int itemEvents;
        private int markerLines;
        private int malformed;
        private boolean truncated;

        public Tap(Consumer<String> downstream) {
            this(downstream, null);
        }

        /**
         * @param onItem 逐题事件回调（可空 = 只收不推）；回调抛异常不影响执行
         */
        public Tap(Consumer<String> downstream, Consumer<Map<String, Object>> onItem) {
            this.downstream = downstream;
            this.onItem = onItem;
        }

        @Override
        public void accept(String line) {
            String text = line == null ? "" : line;
            if (isMarker(text, REPORT)) {
                take(REPORT, text).ifPresent(r -> report = r); // 重复打印取最后一个
                return;
            }
            if (isMarker(text, FINGERPRINT)) {
                take(FINGERPRINT, text).ifPresent(f -> fingerprint = f);
                return;
            }
            if (isMarker(text, ITEM)) {
                take(ITEM, text).ifPresent(this::emitItem);
                return;
            }
            appendLog(text);
            if (downstream != null) {
                downstream.accept(text);
            }
        }

        /** 计数 + 解码；解不出来时计数并让上层按"没有这份数据"走 */
        private Optional<Map<String, Object>> take(String marker, String line) {
            markerLines++;
            Optional<Map<String, Object>> value = LabMarkers.decode(marker, line);
            if (value.isEmpty()) {
                malformed++;
            }
            return value;
        }

        private void emitItem(Map<String, Object> item) {
            itemEvents++;
            if (onItem == null) {
                return;
            }
            try {
                onItem.accept(item);
            } catch (Exception e) {
                log.debug("逐题事件推送失败（不影响执行）: {}", e.getMessage());
            }
        }

        /**
         * 留存人读日志。超长时截断并<b>在日志里明说</b>——静默少掉一段日志，会让人对着
         * "日志里找不到那行"去猜，而那行可能根本不在这份留存里。
         */
        private void appendLog(String text) {
            synchronized (logs) {
                if (logs.length() >= MAX_LOG_CHARS) {
                    if (!truncated) {
                        truncated = true;
                        logs.append("…[日志过长，超出 ").append(MAX_LOG_CHARS)
                                .append(" 字符的部分不再留存；节点侧仍有完整输出]\n");
                    }
                    return;
                }
                logs.append(text).append('\n');
            }
        }

        private static boolean isMarker(String line, String marker) {
            return line.strip().startsWith(marker + " ");
        }

        /** 人读的日志全量（已剔 marker 行） */
        public String logText() {
            synchronized (logs) {
                return logs.toString();
            }
        }

        /** 最终报告（脚本没打 / 解不出来 = empty） */
        public Optional<Map<String, Object>> report() {
            return Optional.ofNullable(report);
        }

        /** 产物指纹（评测不打；微调结束打） */
        public Optional<Map<String, Object>> fingerprint() {
            return Optional.ofNullable(fingerprint);
        }

        public int itemEvents() {
            return itemEvents;
        }

        /** 收到的 marker 行数（诊断用：报告缺失时先看它是不是压根没打） */
        public int markerLines() {
            return markerLines;
        }

        public int malformed() {
            return malformed;
        }
    }
}
