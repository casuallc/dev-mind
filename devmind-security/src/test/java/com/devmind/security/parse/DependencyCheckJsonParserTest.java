package com.devmind.security.parse;

import com.devmind.security.engine.NormalizedFinding;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-66 FR-02：dependency-check JSON 归一化关键语义——
 * purl 坐标解析与回落、CVSS 分段（不信引擎 severity 串）、isVirtual 跳过、
 * 引擎原生 suppression 透传标记、CPE 证据与 NVD 版本区间透传。
 */
class DependencyCheckJsonParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String depJson(String fileName, String packages, String vulns, String extra) {
        return """
                {"dependencies":[
                  {"fileName":"%s","isVirtual":false,"scope":"compile",
                   "packages":[%s],
                   "vulnerabilityIds":{"vulnerabilityIds":[{"id":"cpe:2.3:a:x:y:1.0:*:*:*:*:*:*:*"}]},
                   "vulnerabilities":[%s]%s}
                ]}""".formatted(fileName, packages, vulns, extra);
    }

    private static String vulnJson(String cve, Object baseScore) {
        return """
                {"name":"%s","source":"NVD","severity":"Critical","description":"desc",
                 "cvssv3":{"baseScore":%s,"attackVector":"NETWORK"},
                 "cwes":["CWE-502"],
                 "vulnerableSoftware":[{"software":{"id":"cpe:2.3:a:apache:log4j",
                    "versionStartIncluding":"2.0","versionEndExcluding":"2.17.1"}}]}"""
                .formatted(cve, baseScore);
    }

    @Test
    void purl坐标解析与严重度分段() {
        String json = depJson("log4j-core-2.14.1.jar",
                "{\"id\":\"pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1\"}",
                vulnJson("CVE-2021-44228", 10.0), "");
        List<NormalizedFinding> out = DependencyCheckJsonParser.parse(json.getBytes(StandardCharsets.UTF_8), mapper);
        assertEquals(1, out.size());
        NormalizedFinding f = out.get(0);
        assertEquals("sca", f.engine());
        assertEquals("CVE-2021-44228", f.cve());
        assertEquals("org.apache.logging.log4j", f.groupId());
        assertEquals("log4j-core", f.artifactId());
        assertEquals("2.14.1", f.depVersion());
        assertEquals(NormalizedFinding.CRITICAL, f.severity());
        assertEquals("compile", f.depScope());
        // detail 透传：cvss/cwe/证据/版本区间
        assertEquals(10.0, (Double) f.detail().get("cvssScore"), 0.001);
        assertTrue(f.detail().containsKey("versionRanges"));
        assertTrue(f.detail().containsKey("evidence"));
    }

    @Test
    void severity边界值分段() {
        assertEquals(NormalizedFinding.CRITICAL, DependencyCheckJsonParser.severityOf(9.0));
        assertEquals(NormalizedFinding.HIGH, DependencyCheckJsonParser.severityOf(7.0));
        assertEquals(NormalizedFinding.MEDIUM, DependencyCheckJsonParser.severityOf(4.0));
        assertEquals(NormalizedFinding.LOW, DependencyCheckJsonParser.severityOf(0.1));
        assertEquals(NormalizedFinding.INFO, DependencyCheckJsonParser.severityOf(0.0));
        assertEquals(NormalizedFinding.INFO, DependencyCheckJsonParser.severityOf(null));
    }

    @Test
    void purl缺失时回落fileName剥版本() {
        String json = depJson("log4j-core-2.14.1.jar", "", vulnJson("CVE-2021-44228", 9.8), "");
        List<NormalizedFinding> out = DependencyCheckJsonParser.parse(json.getBytes(StandardCharsets.UTF_8), mapper);
        assertEquals(1, out.size());
        NormalizedFinding f = out.get(0);
        assertNull(f.groupId());
        assertEquals("log4j-core", f.artifactId());
        assertEquals("2.14.1", f.depVersion());
    }

    @Test
    void 虚拟依赖跳过且引擎原生suppression透传标记() {
        String json = """
                {"dependencies":[
                  {"fileName":"virtual.jar","isVirtual":true,
                   "vulnerabilities":[%s]},
                  {"fileName":"a-1.0.jar","isVirtual":false,"packages":[],
                   "vulnerabilities":[],
                   "suppressedVulnerabilities":[%s]}
                ]}""".formatted(vulnJson("CVE-1111-0001", 5.0), vulnJson("CVE-2222-0002", 5.0));
        List<NormalizedFinding> out = DependencyCheckJsonParser.parse(json.getBytes(StandardCharsets.UTF_8), mapper);
        assertEquals(1, out.size()); // isVirtual 跳过
        assertEquals("CVE-2222-0002", out.get(0).cve());
        assertEquals(Boolean.TRUE, out.get(0).detail().get("engineSuppressed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 无cvss分数时severity为INFO且版本区间字段齐全() {
        String json = """
                {"dependencies":[{"fileName":"b-2.0.jar","isVirtual":false,"packages":[],
                  "vulnerabilities":[{"name":"CVE-3333-0003","source":"NVD","description":"d",
                    "vulnerableSoftware":[{"software":{"id":"cpe:2.3:a:x:b",
                       "versionEndIncluding":"2.0"}}]}]}]}""";
        List<NormalizedFinding> out = DependencyCheckJsonParser.parse(json.getBytes(StandardCharsets.UTF_8), mapper);
        assertEquals(1, out.size());
        assertEquals(NormalizedFinding.INFO, out.get(0).severity());
        List<Map<String, String>> ranges = (List<Map<String, String>>) out.get(0).detail().get("versionRanges");
        assertEquals("2.0", ranges.get(0).get("versionEndIncluding"));
    }
}
