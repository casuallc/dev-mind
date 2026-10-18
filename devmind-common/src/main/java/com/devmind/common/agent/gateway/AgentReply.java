package com.devmind.common.agent.gateway;

import java.util.List;

/**
 * copilot → bot 的一轮回复：至少有一个非空字段。
 *
 * @param text   文本回复（纯文本渠道直接发送）
 * @param card   可选卡片；null = 只用文本
 * @param chunks 可选长文本分段（CAP-76 FR-11 危险操作完整预览）：非空时 bot 必须逐段原样发送，
 *               不得套用 {@code reply-max-chars} 截断；每段是独立消息。
 */
public record AgentReply(String text, AgentCard card, List<String> chunks) {

    public AgentReply {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public static AgentReply text(String text) {
        return new AgentReply(text, null, List.of());
    }

    public static AgentReply card(AgentCard card) {
        return new AgentReply(null, card, List.of());
    }

    public static AgentReply of(String text, AgentCard card) {
        return new AgentReply(text, card, List.of());
    }

    public static AgentReply chunked(AgentCard card, List<String> chunks) {
        return new AgentReply(null, card, chunks);
    }

    public String plainText() {
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (!chunks.isEmpty()) {
            return String.join("\n\n", chunks);
        }
        return card == null ? "" : card.toPlainText();
    }
}
