package com.devmind.common.agent.gateway;

/**
 * bot → copilot 的一轮请求（CAP-76 FR-08/FR-19）。
 *
 * @param channel          渠道（feishu/…）
 * @param chatType         p2p / group
 * @param externalChatId   平台会话 id
 * @param senderId         平台发送者 id（会话键的一部分）
 * @param actor            平台账号（binding 结果）
 * @param personalActor    个人绑定命中结果；空 = 该 sender 未绑定（写操作门禁见 FR-19）
 * @param identitySupported 该渠道是否支持个人身份映射（bot 按渠道能力填）
 * @param text             用户原文
 * @param projectId        默认项目 id（bot 绑定；不是授权依据）
 * @param projectName      默认项目名（可空）
 * @param messageId        平台消息 id（可空）
 * @param eventId          平台事件 id（可空）
 */
public record AgentRequest(
        String channel,
        String chatType,
        String externalChatId,
        String senderId,
        String actor,
        String personalActor,
        boolean identitySupported,
        String text,
        String projectId,
        String projectName,
        String messageId,
        String eventId) {

    public AgentRequest {
        text = text == null ? "" : text;
    }
}
