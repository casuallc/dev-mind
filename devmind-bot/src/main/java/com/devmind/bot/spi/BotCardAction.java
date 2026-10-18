package com.devmind.bot.spi;

import java.util.Map;

/**
 * 卡片按钮回调归一化记录（CAP-76 FR-24）：适配器把平台原生回调（飞书
 * {@code card.action.trigger}）翻译成此结构交给底座。
 *
 * <p>{@code value} 是按钮 value 原样（只允许携带操作与 action id）——
 * 身份、chat、消息与事件字段一律以回调事件为准，绝不信 value；底座据此填充
 * {@code AgentCardAction} 的 senderId / actor / personalActor。</p>
 *
 * @param channel        平台 code
 * @param externalChatId 平台会话 id
 * @param chatType       p2p / group（平台未提供时用缓存绑定或 null）
 * @param senderId       点击者平台 id（来自回调事件）
 * @param messageId      被点击的卡片消息 id（原地更新锚点）
 * @param eventId        回调事件 id（去重键）
 * @param value          按钮 value（如 {"devmind":"copilot_confirm","id":"..."}）
 */
public record BotCardAction(
        String channel,
        String externalChatId,
        String chatType,
        String senderId,
        String messageId,
        String eventId,
        Map<String, Object> value) {
}
