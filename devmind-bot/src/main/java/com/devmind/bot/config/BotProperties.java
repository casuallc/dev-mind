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

    public String getDefaultActor() { return defaultActor; }
    public void setDefaultActor(String defaultActor) { this.defaultActor = defaultActor; }
    public int getQaTimeoutSeconds() { return qaTimeoutSeconds; }
    public void setQaTimeoutSeconds(int qaTimeoutSeconds) { this.qaTimeoutSeconds = qaTimeoutSeconds; }
    public int getReplyMaxChars() { return replyMaxChars; }
    public void setReplyMaxChars(int replyMaxChars) { this.replyMaxChars = replyMaxChars; }
    public String getPlatformBaseUrl() { return platformBaseUrl; }
    public void setPlatformBaseUrl(String platformBaseUrl) { this.platformBaseUrl = platformBaseUrl; }
}
