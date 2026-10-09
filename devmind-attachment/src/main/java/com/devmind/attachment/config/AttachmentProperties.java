package com.devmind.attachment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.attachment.* — CAP-32 公共附件管理配置。主类 @ConfigurationPropertiesScan 全局扫描，无需注册。
 */
@ConfigurationProperties(prefix = "devmind.attachment")
public class AttachmentProperties {

    /** 附件存储根目录；空 = ${user.dir}/data/attachments */
    private String rootDir = "";
    /** 单附件大小上限（MB）；同时受 spring.servlet.multipart.max-file-size 约束 */
    private int maxSizeMb = 20;
    /** CAP-68：过期附件清理 cron（默认每天 03:40 低峰）；硬删 expires_at < now，不做引用检查 */
    private String cleanupCron = "0 40 3 * * *";

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }
    public int getMaxSizeMb() { return maxSizeMb; }
    public void setMaxSizeMb(int maxSizeMb) { this.maxSizeMb = maxSizeMb; }
    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
