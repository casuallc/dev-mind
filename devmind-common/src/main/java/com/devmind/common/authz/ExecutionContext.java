package com.devmind.common.authz;

/**
 * CAP-108 §4.2.1 可信执行上下文。只能由认证/调度适配器构造（Web 过滤器、工具执行器、异步派发点）；
 * 客户端提交的 projectId/ownerId/authorized 不作为授权事实，也不进入本对象。
 * 上下文中不含 secret；token/确认单里的角色快照不被信任，判定一律实时重读。
 */
public record ExecutionContext(
        SubjectType subjectType,
        /** 稳定主体 ID（users.id / 集成主体 id / 系统任务名） */
        String subjectId,
        /** 展示与兼容 created_by 用 */
        String username,
        String clientId,
        /** 凭证引用（无 secret） */
        String credentialId,
        ExecutionSource source,
        String traceId,
        String commandId,
        String conversationId,
        String turnId,
        /** AI 确认凭据（服务端确认单 id），不进入模型参数 schema */
        String actionId,
        /** 幂等受理键（服务端执行元数据） */
        String operationKey) {

    /** AI 来源（内置助手 Web/机器人）：写命令需服务端确认凭据。 */
    public boolean aiSourced() {
        return source == ExecutionSource.COPILOT_WEB || source == ExecutionSource.BOT;
    }

    public static Builder builder(SubjectType subjectType, String username, ExecutionSource source) {
        return new Builder(subjectType, username, source);
    }

    /** 服务端可信系统任务（CAP-108 §4.2.1：必须有登记的任务类型与允许操作，M1 由受信代码显式建立）。 */
    public static ExecutionContext system(String taskName) {
        return new Builder(SubjectType.SYSTEM, taskName, ExecutionSource.SYSTEM)
                .subjectId(taskName).build();
    }

    public static final class Builder {
        private final SubjectType subjectType;
        private final String username;
        private final ExecutionSource source;
        private String subjectId;
        private String clientId;
        private String credentialId;
        private String traceId;
        private String commandId;
        private String conversationId;
        private String turnId;
        private String actionId;
        private String operationKey;

        private Builder(SubjectType subjectType, String username, ExecutionSource source) {
            this.subjectType = subjectType;
            this.username = username;
            this.source = source;
        }

        public Builder subjectId(String v) { this.subjectId = v; return this; }
        public Builder clientId(String v) { this.clientId = v; return this; }
        public Builder credentialId(String v) { this.credentialId = v; return this; }
        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder commandId(String v) { this.commandId = v; return this; }
        public Builder conversationId(String v) { this.conversationId = v; return this; }
        public Builder turnId(String v) { this.turnId = v; return this; }
        public Builder actionId(String v) { this.actionId = v; return this; }
        public Builder operationKey(String v) { this.operationKey = v; return this; }

        public ExecutionContext build() {
            if (subjectType == null || source == null) {
                throw new IllegalStateException("subjectType/source 必填");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalStateException("username 必填");
            }
            return new ExecutionContext(subjectType, subjectId, username, clientId, credentialId,
                    source, traceId, commandId, conversationId, turnId, actionId, operationKey);
        }
    }
}
