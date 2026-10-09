package com.devmind.test.service;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-69 marker 通道：脚本打单行、服务端从日志流里捞。断言点与 CAP-56 LabMarkersTest 同源——
 * 回传要穿过 base64+gzip 两层才到服务端，任何一层静默失败，页面上都会变成
 * "跑完了但没有结果"，所以坏法必须可被区分（markerLines 计数）而不是假装没发生。
 */
class ScriptMarkersTest {

    @Test
    void xmlSurvivesRoundTripAndMarkerLinesStayOutOfHumanLog() {
        List<String> pushed = new ArrayList<>();
        ScriptMarkers.Tap tap = new ScriptMarkers.Tap(pushed::add);
        String xml = "<testsuite><testcase name=\"中文用例\" time=\"0.1\"/></testsuite>";

        tap.accept("running tests...");
        tap.accept(ScriptMarkers.encode(xml));
        tap.accept("done");

        assertEquals(xml, tap.junitXml().orElseThrow());
        assertEquals("running tests...\ndone\n", tap.logText());
        assertEquals(List.of("running tests...", "done"), pushed);
        assertEquals(1, tap.markerLines());
    }

    @Test
    void emptyPayloadMeansNoReportNotMalformed() {
        // 命令包装里 junit 文件缺失时 p 为空 → marker 行带空载荷：这是"没有报告"，不该刷屏 warn
        ScriptMarkers.Tap tap = new ScriptMarkers.Tap(null);
        tap.accept(ScriptMarkers.JUNIT + " ");
        assertTrue(tap.junitXml().isEmpty());
        assertEquals(1, tap.markerLines());
    }

    @Test
    void garbagePayloadIsDroppedButCounted() {
        ScriptMarkers.Tap tap = new ScriptMarkers.Tap(null);
        tap.accept(ScriptMarkers.JUNIT + " !!!not-base64!!!");
        assertTrue(tap.junitXml().isEmpty());
        assertEquals(1, tap.markerLines());
    }

    @Test
    void repeatedMarkerTakesTheLastOne() {
        ScriptMarkers.Tap tap = new ScriptMarkers.Tap(null);
        tap.accept(ScriptMarkers.encode("<testsuite tests=\"1\"/>"));
        tap.accept(ScriptMarkers.encode("<testsuite tests=\"2\"/>"));
        assertEquals("<testsuite tests=\"2\"/>", tap.junitXml().orElseThrow());
    }

    @Test
    void wrapCommandGluesTailToLastLineSoAllowlistSeesOnlySuiteCommands() {
        // runner execAllowlist 逐行校验首 token：尾段独立成行会被当成白名单外命令拒绝
        String wrapped = TestRunService.wrapScriptCommand("npm ci\nnpx playwright test\n", "out/junit.xml");
        assertTrue(wrapped.startsWith("{ npm ci\n"));
        assertTrue(wrapped.contains("npx playwright test; ec=$?;"));
        assertTrue(wrapped.contains("gzip -c out/junit.xml"));
        assertTrue(wrapped.endsWith("exit $ec; }"));
        // 尾换行已 strip：不会出现 ";\n; ec=$?" 这种首 token 为 ";" 的悬空行
        assertTrue(wrapped.lines().noneMatch(l -> l.strip().startsWith(";")));
    }
}
