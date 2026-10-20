package com.devmind.common.notification;

/**
 * 向来源聊天推送文本的最小 SPI（CAP-76 FR-34）：异步计划收口后由 copilot 调用，
 * devmind-bot 实现（按 channel 找适配器发送）。
 *
 * <p>推送是增强能力：调用方以 {@code ObjectProvider<AgentChatPush>} 探测，SPI 缺席
 * （bot 模块未装配）静默跳过，推送失败不阻断业务收口。</p>
 */
public interface AgentChatPush {

    /**
     * 向指定聊天推一条文本消息。
     *
     * @param channel        bot 渠道（feishu/…）
     * @param externalChatId 平台侧聊天 id
     * @param text           推送文本（实现方负责平台长度截断）
     */
    void pushText(String channel, String externalChatId, String text);
}
