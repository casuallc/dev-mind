package com.devmind.common.notification;

/**
 * bot 聊天终态推送订阅 SPI（CAP-75 FR-25）：执行侧（CAP-76 工具执行漏斗）在异步作业
 * 受理成功后登记，实现方（devmind-bot）在该实体终态事件到达时向来源聊天推一条结果消息。
 *
 * <p>一次性语义：命中即消费，推送失败不补推。{@link #SESSION} 实体由实现方路由进
 * FR-20 会话 watch（富文案推送），其余实体（BUILD/DEPLOYMENT/TEST_RUN）走通用作业 watch。
 * 实现方缺席（bot 模块未装配）时调用方以 ObjectProvider 探测并静默跳过——
 * 推送是增强能力，不阻断作业受理回执。</p>
 */
public interface BotCompletionWatch {

    /** 会话实体类型（路由 FR-20 会话 watch，label 传任务描述）。 */
    String SESSION = "SESSION";

    /**
     * 登记一条终态推送订阅（同实体重复登记覆盖旧行）。
     *
     * @param entityType     实体类型（BUILD/DEPLOYMENT/TEST_RUN/SESSION）
     * @param entityId       实体 id（构建号/部署号/测试运行号/会话 id）
     * @param channel        bot 渠道（feishu/…）
     * @param externalChatId 平台侧聊天 id（终态消息推到这里）
     * @param label          受理时的描述（推送文案用；SESSION 传任务描述，可空）
     */
    void watch(String entityType, String entityId, String channel, String externalChatId, String label);
}
