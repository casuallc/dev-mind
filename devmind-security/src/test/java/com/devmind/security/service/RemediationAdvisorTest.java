package com.devmind.security.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-66 FR-11-2/3 修复指导：NVD 版本区间 → 最低安全版本（同 major 优先）；
 * 只有 EndIncluding 边界推导不出 → null 不编造；EOL 对照表 → REPLACE；
 * 直接/传递依赖动作分类。
 */
class RemediationAdvisorTest {

    private static List<Map<String, String>> ranges(Map<String, String>... rs) {
        return List.of(rs);
    }

    @Test
    void 当前版本在区间内取endExcluding为修复版() {
        String rec = RemediationAdvisor.recommend("2.14.1", ranges(
                Map.of("versionStartIncluding", "2.0", "versionEndExcluding", "2.17.1")));
        assertEquals("2.17.1", rec);
    }

    @Test
    void 多候选同major优先() {
        String rec = RemediationAdvisor.recommend("2.14.1", ranges(
                Map.of("versionEndExcluding", "3.0.0"),
                Map.of("versionStartIncluding", "2.0", "versionEndExcluding", "2.17.1")));
        assertEquals("2.17.1", rec); // 同 major(2) 优先于跨 major(3)
    }

    @Test
    void 当前版本不在区间内不推荐该边界() {
        String rec = RemediationAdvisor.recommend("2.17.1", ranges(
                Map.of("versionStartIncluding", "2.0", "versionEndExcluding", "2.17.1")));
        assertNull(rec); // 已修复版本不应再被推 2.17.1
    }

    @Test
    void 仅EndIncluding边界推导不出() {
        String rec = RemediationAdvisor.recommend("1.2", ranges(Map.of("versionEndIncluding", "1.2")));
        assertNull(rec);
    }

    @Test
    void 版本比较宽松语义() {
        assertTrue(RemediationAdvisor.compareVersions("2.17.1", "2.9.0") > 0);
        assertTrue(RemediationAdvisor.compareVersions("1.0", "1.0.0") == 0);
        assertTrue(RemediationAdvisor.compareVersions("2.0-alpha", "2.0-beta") < 0);
        assertEquals("2", RemediationAdvisor.majorOf("2.17.1"));
        assertEquals("3", RemediationAdvisor.majorOf("3"));
    }

    @Test
    void 直接依赖动作UPGRADE_DIRECT() {
        Map<String, Object> rem = RemediationAdvisor.advise("org.apache.logging.log4j", "log4j-core", "2.14.1",
                ranges(Map.of("versionStartIncluding", "2.0", "versionEndExcluding", "2.17.1")),
                true, null);
        assertEquals(RemediationAdvisor.UPGRADE_DIRECT, rem.get("action"));
        assertEquals("2.17.1", rem.get("recommendedVersion"));
        assertTrue(rem.get("versionRangesRaw").toString().contains("<2.17.1"));
    }

    @Test
    void 传递依赖动作PIN_TRANSITIVE附引入链() {
        Map<String, Object> rem = RemediationAdvisor.advise("com.x", "lib", "1.0",
                ranges(Map.of("versionEndExcluding", "1.5")),
                false, "com.app:top > com.x:lib");
        assertEquals(RemediationAdvisor.PIN_TRANSITIVE, rem.get("action"));
        assertTrue(rem.get("note").toString().contains("dependencyManagement"));
        assertEquals("com.app:top > com.x:lib", rem.get("depPath"));
    }

    @Test
    void 无修复版命中EOL表给REPLACE() {
        Map<String, Object> rem = RemediationAdvisor.advise("commons-collections", "commons-collections",
                "3.2.1", List.of(), true, null);
        assertEquals(RemediationAdvisor.REPLACE, rem.get("action"));
        assertTrue(rem.get("note").toString().contains("commons-collections4"));
        assertNull(rem.get("recommendedVersion"));
    }

    @Test
    void 无区间无对照表给NONE_AVAILABLE不编造版本() {
        Map<String, Object> rem = RemediationAdvisor.advise("com.x", "lib", "1.0", List.of(), null, null);
        assertEquals(RemediationAdvisor.NONE_AVAILABLE, rem.get("action"));
        assertNull(rem.get("recommendedVersion"));
        assertTrue(rem.get("note").toString().contains("人工查证"));
    }
}
