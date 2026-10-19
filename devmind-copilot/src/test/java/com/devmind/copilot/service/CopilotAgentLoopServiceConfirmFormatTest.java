package com.devmind.copilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 幻觉确认单护栏的格式识别与历史消毒（真机事故回归：deepseek-flash 抄历史里的确认单格式
 * 编造假短码 H7NR，用户回复「确认 H7NR」→ 查无此单）。
 */
class CopilotAgentLoopServiceConfirmFormatTest {

    @Test
    void matchesAllPlatformConfirmShapes() {
        // 单操作（confirmations() 非分片形态）
        assertTrue(CopilotAgentLoopService.looksLikeConfirmation(
                "待确认 [H7NR]：\n操作：创建需求\n参数：\n- title: x\n\n回复「确认 H7NR」执行，或「取消 H7NR」。10 分钟内有效。"));
        // 多操作
        assertTrue(CopilotAgentLoopService.looksLikeConfirmation(
                "待确认 2 个操作：\n[H7NR-1] 创建需求：x\n[H7NR-2] 触发构建：y"));
        // 分片形态（instruction 开头）
        assertTrue(CopilotAgentLoopService.looksLikeConfirmation(
                "回复「确认 H7NR」执行，或「取消 H7NR」。10 分钟内有效。\n（完整计划已分 3 条消息发送）"));
        assertTrue(CopilotAgentLoopService.looksLikeConfirmation(
                "请使用完整短码逐张确认（如：确认 H7NR-1）；10 分钟内有效。"));
        // markdown 加粗/引用包装
        assertTrue(CopilotAgentLoopService.looksLikeConfirmation("**待确认 [H7NR]**：创建需求"));
    }

    @Test
    void ignoresNormalAnswers() {
        assertFalse(CopilotAgentLoopService.looksLikeConfirmation(null));
        assertFalse(CopilotAgentLoopService.looksLikeConfirmation(""));
        assertFalse(CopilotAgentLoopService.looksLikeConfirmation("已为你查询到 3 条需求"));
        assertFalse(CopilotAgentLoopService.looksLikeConfirmation("请确认无误后告诉我"));
        assertFalse(CopilotAgentLoopService.looksLikeConfirmation("待确认事项清单如下：1) …"));
        assertFalse(CopilotAgentLoopService.looksLikeConfirmation("确认单状态可以在平台查看"));
    }

    @Test
    void sanitizeReplacesOnlyConfirmShapedContent() {
        String fake = "待确认 [H7NR]：\n操作：创建需求\n\n回复「确认 H7NR」执行。10 分钟内有效。";
        String sanitized = CopilotAgentLoopService.sanitizeHistoryContent(fake);
        assertFalse(sanitized.contains("H7NR"));
        assertFalse(CopilotAgentLoopService.looksLikeConfirmation(sanitized));

        String normal = "已为你查询到 3 条需求";
        assertEquals(normal, CopilotAgentLoopService.sanitizeHistoryContent(normal));
    }
}
