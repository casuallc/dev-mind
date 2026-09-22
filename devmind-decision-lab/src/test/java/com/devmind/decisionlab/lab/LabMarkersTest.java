package com.devmind.decisionlab.lab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-56 marker 通道：脚本打单行、服务端从日志流里捞。
 *
 * <p>这几条断言背后是同一个问题：<b>报告是唯一能证明"模型准不准"的东西</b>，而它要穿过
 * "base64 → gzip → JSON"三层才到服务端。任何一层静默失败，页面上都会变成"跑完了但什么都没说"
 * ——那正是 CAP-56 要消灭的状态。所以这里既测正常往返，也测三种坏法（非 base64、非 gzip、
 * 不是 JSON 对象）都必须<b>被计数</b>（→ 上层能显示 MALFORMED 而不是假装没发生）。</p>
 */
class LabMarkersTest {

    @Test
    void reportSurvivesTheRoundTripAndLeavesNoBase64InTheLog() {
        List<String> pushed = new ArrayList<>();
        LabMarkers.Tap tap = new LabMarkers.Tap(pushed::add);

        Map<String, Object> report = Map.of("schemaVersion", 1, "metrics", Map.of("items", 60));
        tap.accept("人读的一行：开始评测");
        tap.accept(LabMarkers.encode(LabMarkers.REPORT, report));
        tap.accept("人读的一行：结束");

        assertEquals(60, ((Number) ((Map<?, ?>) tap.report().orElseThrow().get("metrics")).get("items")).intValue());
        // 日志里只剩人能读的两行：base64 大块不该混进日志，它另有一等公民的落点（报告列）
        assertEquals("人读的一行：开始评测\n人读的一行：结束\n", tap.logText());
        assertEquals(List.of("人读的一行：开始评测", "人读的一行：结束"), pushed);
        assertEquals(1, tap.markerLines());
        assertEquals(0, tap.malformed());
    }

    @Test
    void repeatedReportTakesTheLastOne() {
        // 脚本中途诊断性地打一次、结束时打一次终版：后者才是结论
        LabMarkers.Tap tap = new LabMarkers.Tap(null);
        tap.accept(LabMarkers.encode(LabMarkers.REPORT, Map.of("phase", "partial")));
        tap.accept(LabMarkers.encode(LabMarkers.REPORT, Map.of("phase", "final")));
        assertEquals("final", tap.report().orElseThrow().get("phase"));
    }

    @Test
    void itemEventsStreamOutInOrderAndAreNotPersistedAsLogs() {
        List<Map<String, Object>> items = new ArrayList<>();
        LabMarkers.Tap tap = new LabMarkers.Tap(null, items::add);
        tap.accept(LabMarkers.encode(LabMarkers.ITEM, Map.of("id", 1, "correct", false)));
        tap.accept(LabMarkers.encode(LabMarkers.ITEM, Map.of("id", 2, "correct", true)));

        assertEquals(2, tap.itemEvents());
        assertEquals(List.of(1L, 2L), items.stream().map(m -> m.get("id")).toList());
        assertEquals("", tap.logText(), "逐题事件是机器载荷，不该进人读日志（最终报告里有全量）");
    }

    @Test
    void aBrokenItemEventDoesNotBreakTheRun() {
        LabMarkers.Tap tap = new LabMarkers.Tap(null, item -> {
            throw new IllegalStateException("推送通道炸了");
        });
        tap.accept(LabMarkers.encode(LabMarkers.ITEM, Map.of("id", 1)));
        tap.accept("后续日志照常");
        assertEquals(1, tap.itemEvents());
        assertEquals("后续日志照常\n", tap.logText());
    }

    @Test
    void fingerprintIsCollectedSeparately() {
        LabMarkers.Tap tap = new LabMarkers.Tap(null);
        tap.accept(LabMarkers.encode(LabMarkers.FINGERPRINT,
                Map.of("path", "/data/out/step-100", "bytes", 1234, "sha256", "a".repeat(64))));
        assertEquals("/data/out/step-100", tap.fingerprint().orElseThrow().get("path"));
        assertTrue(tap.report().isEmpty(), "指纹不是报告：两者不能互相顶掉");
    }

    @Test
    void malformedMarkerLinesAreCountedNotSwallowed() {
        LabMarkers.Tap tap = new LabMarkers.Tap(null);
        tap.accept("DEVMIND_REPORT {\"plain\":\"json 明文，不是 base64\"}");
        tap.accept("DEVMIND_REPORT bm90LWd6aXA=");          // base64 对，但不是 gzip
        tap.accept("DEVMIND_REPORT " + LabMarkers.encode(LabMarkers.REPORT, List.of("数组不是对象"))
                .substring("DEVMIND_REPORT ".length()));

        assertTrue(tap.report().isEmpty());
        assertEquals(3, tap.markerLines());
        assertEquals(3, tap.malformed(), "解不出来的 marker 行要计数——上层据此显示「报告无法解析」而不是「没打报告」");
        assertEquals("", tap.logText(), "marker 行即使是坏的也不进人读日志（避免半截 base64 噪声）");
    }

    @Test
    void decodeIgnoresOtherMarkersAndPlainLines() {
        assertTrue(LabMarkers.decode(LabMarkers.REPORT, "DEVMIND_ITEM abc").isEmpty());
        assertTrue(LabMarkers.decode(LabMarkers.REPORT, "普通日志行").isEmpty());
        assertTrue(LabMarkers.decode(LabMarkers.REPORT, null).isEmpty());
    }

    @Test
    void nestedPayloadBecomesPlainMapsListsAndNumbers() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metrics", Map.of("choice", Map.of("accuracy", 0.72)));
        payload.put("perItem", List.of(Map.of("id", 3, "correct", false, "latencyMs", 11.5)));
        payload.put("note", null);

        Map<String, Object> decoded = LabMarkers.decode(LabMarkers.REPORT,
                LabMarkers.encode(LabMarkers.REPORT, payload)).orElseThrow();

        Map<?, ?> choice = (Map<?, ?>) ((Map<?, ?>) decoded.get("metrics")).get("choice");
        assertEquals(0.72, (Double) choice.get("accuracy"), 1e-9);
        List<?> perItem = (List<?>) decoded.get("perItem");
        Map<?, ?> first = (Map<?, ?>) perItem.get(0);
        assertEquals(3L, first.get("id"));
        assertEquals(Boolean.FALSE, first.get("correct"));
        assertEquals(11.5, (Double) first.get("latencyMs"), 1e-9);
        assertNull(decoded.get("note"));
    }

    @Test
    void logIsTruncatedWithAVisibleNotice() {
        // 4MB 的留存上限：越过之后必须留下"这里少了一段"，否则人会对着日志猜为什么没有那行
        LabMarkers.Tap tap = new LabMarkers.Tap(null);
        String chunk = "x".repeat(1_000_000);
        for (int i = 0; i < 5; i++) {
            tap.accept(chunk);
        }
        String logs = tap.logText();
        assertTrue(logs.length() < 5_000_000);
        assertTrue(logs.contains("日志过长"), "截断必须在日志里明说");
    }

    @Test
    void markersWithLeadingWhitespaceAreStillRecognized() {
        // 脚本可能被包一层前缀（如 [eval] DEVMIND_REPORT …）；此时不认也没关系，
        // 但认得的边界要钉住：前导空白可以被 strip，行内前缀不行
        LabMarkers.Tap tap = new LabMarkers.Tap(null);
        tap.accept("   " + LabMarkers.encode(LabMarkers.REPORT, Map.of("ok", true)));
        assertTrue(tap.report().isPresent());
        assertFalse(tap.logText().contains("DEVMIND_REPORT"));
    }
}
