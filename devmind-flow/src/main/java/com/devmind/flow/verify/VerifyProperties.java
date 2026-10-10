package com.devmind.flow.verify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-73 验证链平台配置。
 */
@ConfigurationProperties(prefix = "devmind.verify")
public class VerifyProperties {

    /** 产物包大小上限（MB），超限构建侧失败 VERIFY_BUNDLE_TOO_LARGE */
    private int bundleMaxMb = 512;

    /** verify 作用域 token 密钥；空 = 由 devmind.auth.jwt-secret 派生 */
    private String tokenSecret = "";

    /** agent 触发 token 有效期（小时；会话结束即失效由触发端存活检查兜底） */
    private int tokenTtlHours = 24;

    /** 产物包暂存目录（成功即删，失败保留 bundleRetentionDays 天） */
    private String bundleDir = "data/verify-bundles";

    /** 失败 run 产物包保留天数 */
    private int bundleRetentionDays = 7;

    /** PUSH 阶段超时（秒） */
    private long pushTimeoutSec = 300;

    /** BUILD 阶段超时（秒） */
    private long buildTimeoutSec = 1800;

    /** PATCH 阶段超时（秒） */
    private long patchTimeoutSec = 600;

    /** TEST 阶段超时（秒；冒烟套件运行轮询上限） */
    private long testTimeoutSec = 1800;

    public int getBundleMaxMb() { return bundleMaxMb; }
    public void setBundleMaxMb(int bundleMaxMb) { this.bundleMaxMb = bundleMaxMb; }
    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String tokenSecret) { this.tokenSecret = tokenSecret; }
    public int getTokenTtlHours() { return tokenTtlHours; }
    public void setTokenTtlHours(int tokenTtlHours) { this.tokenTtlHours = tokenTtlHours; }
    public String getBundleDir() { return bundleDir; }
    public void setBundleDir(String bundleDir) { this.bundleDir = bundleDir; }
    public int getBundleRetentionDays() { return bundleRetentionDays; }
    public void setBundleRetentionDays(int bundleRetentionDays) { this.bundleRetentionDays = bundleRetentionDays; }
    public long getPushTimeoutSec() { return pushTimeoutSec; }
    public void setPushTimeoutSec(long pushTimeoutSec) { this.pushTimeoutSec = pushTimeoutSec; }
    public long getBuildTimeoutSec() { return buildTimeoutSec; }
    public void setBuildTimeoutSec(long buildTimeoutSec) { this.buildTimeoutSec = buildTimeoutSec; }
    public long getPatchTimeoutSec() { return patchTimeoutSec; }
    public void setPatchTimeoutSec(long patchTimeoutSec) { this.patchTimeoutSec = patchTimeoutSec; }
    public long getTestTimeoutSec() { return testTimeoutSec; }
    public void setTestTimeoutSec(long testTimeoutSec) { this.testTimeoutSec = testTimeoutSec; }
}
