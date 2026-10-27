package com.devmind.worklog.service;

import com.devmind.worklog.dto.SuggestedObjective;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-93 FR-02 OkrSuggestParser 单测：行契约（O/KR 两级、权重、父O#id）、容错
 * （不合规行丢弃/围栏/无父 O 的 KR）、封顶（5 O、每 O 6 KR）。
 */
class OkrSuggestParserTest {

    @Test
    void 标准两级列表解析() {
        List<SuggestedObjective> items = OkrSuggestParser.parse(
                "- [O] 提升交付质量（权重 60%）（父O#12）\n"
                        + "  - [KR] 缺陷率降至 1%（权重 30%）\n"
                        + "  - [KR] 发布自动化覆盖 80%\n"
                        + "- [O] 完成平台 v2 改版\n"
                        + "  - [KR] 迁移 20 个存量页面\n");
        assertEquals(2, items.size());
        SuggestedObjective o1 = items.get(0);
        assertEquals("提升交付质量", o1.title());
        assertEquals(60, o1.weight());
        assertEquals(12L, o1.parentYearGoalId());
        assertEquals(2, o1.krs().size());
        assertEquals("缺陷率降至 1%", o1.krs().get(0).title());
        assertEquals(30, o1.krs().get(0).weight());
        assertNull(o1.krs().get(1).weight());
        assertNull(items.get(1).parentYearGoalId());
    }

    @Test
    void 全角括号与星号列表兼容() {
        List<SuggestedObjective> items = OkrSuggestParser.parse(
                "* [o] 目标一 (权重 50%)\n"
                        + "  * [kr] 关键结果一\n");
        assertEquals(1, items.size());
        assertEquals(50, items.get(0).weight());
        assertEquals(1, items.get(0).krs().size());
    }

    @Test
    void 不合规行静默丢弃() {
        List<SuggestedObjective> items = OkrSuggestParser.parse(
                "# 这是标题\n"
                        + "解释一下我的想法\n"
                        + "- 没有类型标注的行\n"
                        + "- [O] 合法目标\n");
        assertEquals(1, items.size());
        assertEquals("合法目标", items.get(0).title());
    }

    @Test
    void 围栏代码块内行不算() {
        List<SuggestedObjective> items = OkrSuggestParser.parse(
                "```\n- [O] 示例里的目标\n```\n- [O] 真目标\n");
        assertEquals(1, items.size());
        assertEquals("真目标", items.get(0).title());
    }

    @Test
    void 无前置O的KR丢弃() {
        List<SuggestedObjective> items = OkrSuggestParser.parse(
                "  - [KR] 孤儿关键结果\n- [O] 真目标\n");
        assertEquals(1, items.size());
        assertTrue(items.get(0).krs().isEmpty());
    }

    @Test
    void 权重越界置空() {
        List<SuggestedObjective> items = OkrSuggestParser.parse("- [O] 目标（权重 150%）\n");
        assertNull(items.get(0).weight());
    }

    @Test
    void 封顶五个O每O六个KR() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            sb.append("- [O] 目标").append(i).append('\n');
            for (int j = 1; j <= 8; j++) {
                sb.append("  - [KR] 关键结果").append(i).append('-').append(j).append('\n');
            }
        }
        List<SuggestedObjective> items = OkrSuggestParser.parse(sb.toString());
        assertEquals(OkrSuggestParser.MAX_OBJECTIVES, items.size());
        assertTrue(items.stream().allMatch(o -> o.krs().size() == OkrSuggestParser.MAX_KRS));
    }

    @Test
    void 空内容与空白返回空() {
        assertTrue(OkrSuggestParser.parse(null).isEmpty());
        assertTrue(OkrSuggestParser.parse("  \n ").isEmpty());
    }
}
