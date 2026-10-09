package com.devmind.test.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CAP-69 脚本套件 ↔ 服务端的数据通道（CAP-56 {@code LabMarkers} 先例的精简版，契约独立演化）。
 *
 * <p><b>为什么只能这样</b>：exec 链路没有任何文件上行通道（{@code AgentExecResult} 只有退出码，
 * 产出靠 stdout 行流）。脚本套件跑完要回传的 JUnit XML 可能上百 KB，唯一可靠办法是脚本往 stdout
 * 打单行 {@code DEVMIND_JUNIT <base64(gzip(xml))>}，服务端从日志流里捞出来。</p>
 *
 * <p><b>与 LabMarkers 的差异</b>：载荷是原始 XML 字符串而非 JSON Map——JUnit XML 不需要也不能
 * 无损转成 JSON 中间态，省一层无谓转换。</p>
 *
 * <p><b>解码失败不抛</b>：marker 打坏了不该让整次运行变成"没跑过"。解不出来记 warn、按
 * "没有这份数据"处理（run 收尾会落成可见的 errorSummary 注记），人读日志与退出码仍完整。</p>
 */
public final class ScriptMarkers {

    private static final Logger log = LoggerFactory.getLogger(ScriptMarkers.class);

    /** JUnit XML 回传 marker（命令包装尾段打印，与服务端解码是一份契约） */
    public static final String JUNIT = "DEVMIND_JUNIT";

    private ScriptMarkers() {
    }

    /** 编码（单测与脚本侧对齐用；生产路径由命令包装里的 gzip|base64 完成） */
    public static String encode(String xmlUtf8) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
                gz.write(xmlUtf8.getBytes(StandardCharsets.UTF_8));
            }
            return JUNIT + " " + Base64.getEncoder().encodeToString(raw.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("marker 编码失败: " + e.getMessage(), e);
        }
    }

    /** 解码一行；不是 marker 行 / 载荷为空 / 解不出来都返回 empty（解失败已记日志） */
    public static Optional<String> decode(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String trimmed = line.strip();
        String head = JUNIT + " ";
        if (!trimmed.startsWith(head)) {
            return Optional.empty();
        }
        String b64 = trimmed.substring(head.length()).strip();
        if (b64.isEmpty()) {
            // 命令包装里 junit 文件缺失时 gzip 产出为空：这是"没有报告"，不是"报告坏了"
            return Optional.empty();
        }
        try {
            byte[] gz = Base64.getDecoder().decode(b64);
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("DEVMIND_JUNIT 载荷解析失败（按无报告继续）: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 日志分流器：人读的行原样转发给下游（ExecutionLogHub）并攒一份落库；
     * marker 行<b>不转发也不落库</b>（base64 大块混在日志里没人看），载荷收进 {@link #junitXml()}。
     * 重复打印取最后一个（脚本中途诊断打一次、结束打终版，后者才是结论）。
     */
    public static final class Tap implements Consumer<String> {

        private static final int MAX_LOG_CHARS = 4_000_000;

        private final Consumer<String> downstream;
        private final StringBuilder logs = new StringBuilder();
        private String junitXml;
        private int markerLines;
        private boolean truncated;

        public Tap(Consumer<String> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void accept(String line) {
            String text = line == null ? "" : line;
            if (text.strip().startsWith(JUNIT)) {
                markerLines++;
                ScriptMarkers.decode(text).ifPresent(xml -> junitXml = xml);
                return;
            }
            synchronized (logs) {
                if (logs.length() >= MAX_LOG_CHARS) {
                    if (!truncated) {
                        truncated = true;
                        logs.append("…[日志过长，超出 ").append(MAX_LOG_CHARS)
                                .append(" 字符的部分不再留存；节点侧仍有完整输出]\n");
                    }
                } else {
                    logs.append(text).append('\n');
                }
            }
            if (downstream != null) {
                downstream.accept(text);
            }
        }

        /** 人读日志全量（已剔 marker 行） */
        public String logText() {
            synchronized (logs) {
                return logs.toString();
            }
        }

        /** 回传的 JUnit XML（脚本没打 / 文件缺失 / 解不出来 = empty） */
        public Optional<String> junitXml() {
            return Optional.ofNullable(junitXml);
        }

        /** 收到的 marker 行数（诊断用：报告缺失时先看它是不是压根没打） */
        public int markerLines() {
            return markerLines;
        }
    }
}
