package com.devmind.common.agent.tool;

import java.time.Instant;

/**
 * 工具执行上下文（CAP-76 FR-02）。<b>全部由服务端填充</b>，工具不得接受模型传入的同名字段。
 *
 * @param turnId          当前对话轮 id
 * @param actionId        确认单 id（对话路径为 null；确认执行时非空）
 * @param actor           平台账号
 * @param role            实时解析的角色（ADMIN/DEVELOPER/VIEWER）
 * @param channel         bot 渠道（feishu/…）
 * @param externalChatId  平台会话 id
 * @param senderId        平台发送者 id
 * @param personalActor   个人绑定命中的平台账号（空 = 未绑定）
 * @param projectId       默认项目（bot 绑定项目；工具参数可覆盖；<b>不是授权依据</b>）
 * @param projectName     默认项目名（可空）
 * @param now             本轮开始时间
 */
public record ToolContext(
        String turnId,
        String actionId,
        String actor,
        String role,
        String channel,
        String externalChatId,
        String senderId,
        String personalActor,
        String projectId,
        String projectName,
        Instant now) {
}
