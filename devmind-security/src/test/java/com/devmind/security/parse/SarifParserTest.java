package com.devmind.security.parse;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CAP-66 FR-02：SARIF 通用解析——result.level 缺失回落规则 defaultConfiguration.level，
 * 再缺省 warning；level→统一严重度映射；仓库相对路径归一。
 */
class SarifParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void level缺失回落规则默认级别() {
        String sarif = """
                {"version":"2.1.0","runs":[{
                  "tool":{"driver":{"name":"semgrep","rules":[
                    {"id":"java.lang.security.audit.xxe","defaultConfiguration":{"level":"error"},
                     "shortDescription":{"text":"XXE"},"helpUri":"https://r2c.dev/xxe"}]}},
                  "results":[{
                    "ruleId":"java.lang.security.audit.xxe",
                    "message":{"text":"xxe found"},
                    "locations":[{"physicalLocation":{
                       "artifactLocation":{"uri":"./src/main/java/A.java"},
                       "region":{"startLine":42,"snippet":{"text":"  DocumentBuilderFactory.newInstance()  "}}}}]}]}]}""";
        List<SarifParser.SarifResult> out = SarifParser.parse(sarif.getBytes(StandardCharsets.UTF_8), mapper);
        assertEquals(1, out.size());
        SarifParser.SarifResult r = out.get(0);
        assertEquals("error", r.level());
        assertEquals("HIGH", SarifParser.mapLevel(r.level()));
        assertEquals("src/main/java/A.java", r.uri());
        assertEquals(42, r.startLine());
        assertEquals("DocumentBuilderFactory.newInstance()", r.snippet());
        assertEquals("XXE", r.extra().get("ruleDescription"));
        assertEquals("semgrep", r.extra().get("tool"));
    }

    @Test
    void level与规则都缺失时按warning兜底() {
        String sarif = """
                {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"gitleaks","rules":[]}},
                  "results":[{"ruleId":"generic-api-key","message":{"text":"m"},
                    "locations":[{"physicalLocation":{"artifactLocation":{"uri":"a/b.txt"}}}]}]}]}""";
        List<SarifParser.SarifResult> out = SarifParser.parse(sarif.getBytes(StandardCharsets.UTF_8), mapper);
        assertEquals(1, out.size());
        assertEquals("warning", out.get(0).level());
        assertNull(out.get(0).startLine());
    }

    @Test
    void level映射表() {
        assertEquals("HIGH", SarifParser.mapLevel("error"));
        assertEquals("MEDIUM", SarifParser.mapLevel("warning"));
        assertEquals("LOW", SarifParser.mapLevel("note"));
        assertEquals("INFO", SarifParser.mapLevel("none"));
        assertEquals("INFO", SarifParser.mapLevel(null));
    }

    @Test
    void uri归一去点杠与反斜杠() {
        assertEquals("src/A.java", SarifParser.normalizeUri("./src/A.java"));
        assertEquals("src/a/B.java", SarifParser.normalizeUri("src\\a\\B.java"));
        assertNull(SarifParser.normalizeUri(null));
    }
}
