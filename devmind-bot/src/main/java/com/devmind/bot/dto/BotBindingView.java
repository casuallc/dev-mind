package com.devmind.bot.dto;

import java.time.Instant;

/**
 * CAP-75 FR-14 机器人运维页群绑定视图：bot_bindings 一行 + 项目名联查。
 */
public record BotBindingView(Long id, String channel, String externalChatId, String chatType,
                             String ownerSenderId, String chatSessionId,
                             String projectId, String projectName, String actor,
                             Instant createdAt, Instant updatedAt) {
}
