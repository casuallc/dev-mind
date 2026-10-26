package com.devmind.worklog.service;

import com.devmind.worklog.dto.SuggestedPlanItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-88 M3 PlanSuggestParser：契约行解析（类型/预估/容错丢弃/截断/封顶）。
 * 行格式是场景骨架 ↔ prompt ↔ 解析器三方契约（`- [类型] 标题（预估 Xh）`）。
 */
class PlanSuggestParserTest {

    @Test
    void 标准契约行解析() {
        List<SuggestedPlanItem> items = PlanSuggestParser.parse(
                "- [DEV] 承接登录改造收尾（预估 4h）\n"
                        + "- [RESEARCH] 调研分诊模型替换（预估 1.5h）\n"
                        + "- [MEETING] 对齐评审口径\n");
        assertEquals(3, items.size());
        assertEquals(new SuggestedPlanItem("承接登录改造收尾", "DEV", 240), items.get(0));
        assertEquals(new SuggestedPlanItem("调研分诊模型替换", "RESEARCH", 90), items.get(1));
        // 预估可省略 → null；类型小写也认（归大写）
        assertEquals(new SuggestedPlanItem("对齐评审口径", "MEETING", null), items.get(2));
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
