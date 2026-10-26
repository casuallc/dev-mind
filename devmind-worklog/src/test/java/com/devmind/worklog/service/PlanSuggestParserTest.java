package com.devmind.worklog.service;

import com.devmind.worklog.dto.SuggestedPlanItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-88 M3 PlanSuggestParser：契约行解析（类型/预估/容错丢弃/截断/封顶）。
 * 行格式是场景骨架 ↔ prompt ↔ 解析器三方契约（`- [类型] 标题（预估 Xh）（KR#id）`，
 * KR 标注可选，CAP-91 M3 FR-11）。
 */
class PlanSuggestParserTest {

    @Test
    void 标准契约行解析() {
        List<SuggestedPlanItem> items = PlanSuggestParser.parse(
                "- [DEV] 承接登录改造收尾（预估 4h）\n"
                        + "- [RESEARCH] 调研分诊模型替换（预估 1.5h）\n"
                        + "- [MEETING] 对齐评审口径\n");
        assertEquals(3, items.size());
        assertEquals(new SuggestedPlanItem("承接登录改造收尾", "DEV", 240, null), items.get(0));
        assertEquals(new SuggestedPlanItem("调研分诊模型替换", "RESEARCH", 90, null), items.get(1));
        // 预估可省略 → null；类型小写也认（归大写）
        assertEquals(new SuggestedPlanItem("对齐评审口径", "MEETING", null, null), items.get(2));
    }

    @Test
    void KR标注解析_可缺省() {
        List<SuggestedPlanItem> items = PlanSuggestParser.parse(
                "- [DEV] 承接登录改造收尾（预估 4h）（KR#12）\n"
                        + "- [DEV] 只标KR不标预估（KR#7）\n"
                        + "- [RESEARCH] 小写半角也认 (kr# 9)\n"
                        + "- [MEETING] 不标注的行\n");
        assertEquals(4, items.size());
        assertEquals(Long.valueOf(12), items.get(0).krId());
        assertEquals(Integer.valueOf(240), items.get(0).estimateMinutes());
        assertEquals("承接登录改造收尾", items.get(0).title());
        assertEquals(Long.valueOf(7), items.get(1).krId());
        assertNull(items.get(1).estimateMinutes());
        assertEquals("只标KR不标预估", items.get(1).title());
        assertEquals(Long.valueOf(9), items.get(2).krId());
        assertNull(items.get(3).krId(), "未标注为 null");
        assertEquals("不标注的行", items.get(3).title());
    }

    @Test
    void 非数字KR标注不进id字段() {
        // KR#abc 不命中标注组 → 残留进标题（容错不丢行），krId 为 null
        List<SuggestedPlanItem> items = PlanSuggestParser.parse("- [DEV] 某建议（KR#abc）\n");
        assertEquals(1, items.size());
        assertNull(items.get(0).krId());
        assertTrue(items.get(0).title().contains("某建议"));
    }

    @Test
    void 半角括号与星号列表也认() {
        List<SuggestedPlanItem> items = PlanSuggestParser.parse(
                "* [support] 值班答疑 (预估 0.5h)\n- 无类型行回退 DEV\n");
        assertEquals(2, items.size());
        assertEquals("SUPPORT", items.get(0).planType());
        assertEquals(Integer.valueOf(30), items.get(0).estimateMinutes());
        assertEquals("DEV", items.get(1).planType());
        assertNull(items.get(1).estimateMinutes());
    }

    @Test
    void 不合规行静默丢弃() {
        List<SuggestedPlanItem> items = PlanSuggestParser.parse(
                "# 本周建议\n"
                        + "以下是建议：\n"
                        + "```\n- [DEV] 围栏内的不算\n```\n"
                        + "- \n"
                        + "- [DEV] 合规行\n"
                        + "普通文本行\n");
        assertEquals(1, items.size());
        assertEquals("合规行", items.get(0).title());
    }

    @Test
    void 未知类型回退DEV_标题截断() {
        String longTitle = "长".repeat(300);
        List<SuggestedPlanItem> items = PlanSuggestParser.parse(
                "- [HACKING] 未知类型\n- [DEV] " + longTitle + "\n");
        assertEquals("DEV", items.get(0).planType());
        assertEquals(256, items.get(1).title().length());
    }

    @Test
    void 总量封顶() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("- [DEV] 建议").append(i).append('\n');
        }
        assertEquals(PlanSuggestParser.MAX_ITEMS, PlanSuggestParser.parse(sb.toString()).size());
    }

    @Test
    void 空与空白产物返回空表() {
        assertTrue(PlanSuggestParser.parse(null).isEmpty());
        assertTrue(PlanSuggestParser.parse("").isEmpty());
        assertTrue(PlanSuggestParser.parse("  \n \n").isEmpty());
    }
}
