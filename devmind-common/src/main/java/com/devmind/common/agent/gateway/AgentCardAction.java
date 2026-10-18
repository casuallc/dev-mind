package com.devmind.common.agent.gateway;

/**
 * 卡片按钮回调（CAP-76 FR-24）。
 *
 * <p>senderId / eventId / chat 与身份字段一律由 bot 从回调事件与当前身份链填入，
 * 绝不取按钮 value；copilot 依此做「只有发起人本人能确认」与绑定重校验。</p>
 *
 * @param operation        CONFIRM / CANCEL
 * @param actionId         确认单 id
 * @param channel          渠道
 * @param externalChatId   平台会话 id
 * @param senderId         操作者平台 id（来自回调事件）
 * @param eventId          回调事件 id（幂等）
 * @param actor            当前实时解析的平台账号
 * @param personalActor    当前实时的个人绑定结果（空 = 未绑定）
 * @param identitySupported 该渠道是否支持个人身份映射
 */
public record AgentCardAction(
        String operation,
        String actionId,
        String channel,
        String externalChatId,
        String senderId,
        String eventId,
        String actor,
        String personalActor,
        boolean identitySupported) {
}
