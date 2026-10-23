package com.devmind.knowledge.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimHashTest {

    @Test
    void normalizesWhitespaceAndPunctuation() {
        String a = SimHash.of("部署手册：先检查端口，再重启服务。");
        String b = SimHash.of("部署手册  先检查端口\n再重启服务");
        assertNotNull(a);
        assertEquals(a, b);
        assertEquals(0, SimHash.distance(a, b));
    }

    @Test
    void emptyContentHasNoFingerprint() {
        assertNull(SimHash.of(null));
        assertNull(SimHash.of("   \n\t "));
        assertNull(SimHash.parse(null));
        assertNull(SimHash.parse("not-a-hash"));
    }

    @Test
    void nearDuplicateIsCloserThanUnrelatedDocument() {
        String base = "生产环境部署前先检查数据库连接池配置，确认最大连接数与超时时间，然后滚动重启应用节点。";
        String near = base + "重启完成后观察错误日志与接口成功率。";
        String far = "前端表格固定列的写法：给列设置 fixed 属性，并保持 scroll.x 大于所有列宽之和。";

        String a = SimHash.of(base);
        String b = SimHash.of(near);
        String c = SimHash.of(far);
        assertTrue(SimHash.distance(a, b) < SimHash.distance(a, c));
        assertTrue(SimHash.distance(a, b) <= 16);
    }

    @Test
    void bandIsStableAndInRange() {
        String hash = SimHash.of("知识库检索评测数据集与指标说明");
        long raw = SimHash.parse(hash);
        for (int band = 0; band < 4; band++) {
            int value = SimHash.band(raw, band);
            assertEquals(value, SimHash.band(raw, band));
            assertTrue(value >= 0 && value <= 0xFFFF);
        }
    }
}
