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

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }
    public int getMaxSizeMb() { return maxSizeMb; }
    public void setMaxSizeMb(int maxSizeMb) { this.maxSizeMb = maxSizeMb; }
}
