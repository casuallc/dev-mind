package com.devmind.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.bot.* —— 机器人网关底座配置（CAP-75）。
 */
@ConfigurationProperties(prefix = "devmind.bot")
public class BotProperties {

    /** 默认操作身份（绑定未指定 actor 时生效）；问答/会话的 created_by 即此账号 */
    private String defaultActor = "local";

    /** 问答等待回答的超时秒数（超时回复提示，问答本身继续在平台跑） */
    private int qaTimeoutSeconds = 120;

    /** 单条回复最大字符数（超出截断，防平台长度限制/刷屏） */
    private int replyMaxChars = 3500;

    /** 平台外部访问地址（回复尾注链接用；空 = 不带链接） */
    private String platformBaseUrl = "";

    /** FR-24 交互日志保留天数（0 = 不清理）；清理 cron 见 devmind.bot.cleanup-cron */
    private int interactionRetentionDays = 30;

    /**
     * CAP-77 平台自项目 id：`/issue` 固定把平台自身问题登记到此项目。
     * 空 = 未启用（/issue 回配置指引），不回落聊天绑定项目，防误记到客户项目。
     */
    private String selfProjectId = "";

    public String getDefaultActor() { return defaultActor; }
    public void setDefaultActor(String defaultActor) { this.defaultActor = defaultActor; }
    public int getQaTimeoutSeconds() { return qaTimeoutSeconds; }
    public void setQaTimeoutSeconds(int qaTimeoutSeconds) { this.qaTimeoutSeconds = qaTimeoutSeconds; }
    public int getReplyMaxChars() { return replyMaxChars; }
    public void setReplyMaxChars(int replyMaxChars) { this.replyMaxChars = replyMaxChars; }
    public String getPlatformBaseUrl() { return platformBaseUrl; }
    public void setPlatformBaseUrl(String platformBaseUrl) { this.platformBaseUrl = platformBaseUrl; }
    public int getInteractionRetentionDays() { return interactionRetentionDays; }
    public void setInteractionRetentionDays(int interactionRetentionDays) {
        this.interactionRetentionDays = interactionRetentionDays;
    }
    public String getSelfProjectId() { return selfProjectId; }
    public void setSelfProjectId(String selfProjectId) { this.selfProjectId = selfProjectId; }
}
