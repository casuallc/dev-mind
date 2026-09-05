package com.devmind.worklog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-28 工时管理配置（devmind.worklog.*）。主类 @ConfigurationPropertiesScan 全局扫描，无需注册。
 */
@ConfigurationProperties(prefix = "devmind.worklog")
public class WorklogProperties {

    /** 每日定时生成日报草稿开关 */
    private boolean dailyEnabled = true;

    /** 日报生成 cron（默认每日 18:30） */
    private String dailyCron = "0 30 18 * * *";

    /** 每周定时生成周报草稿开关 */
    private boolean weeklyEnabled = true;

    /** 周报生成 cron（默认周一 09:00，汇总上一周） */
    private String weeklyCron = "0 0 9 * * MON";

    /** 单库单日 git log 扫描上限 */
    private int gitScanMaxCommits = 200;

    /** one-shot 总结会话超时（秒） */
    private int oneshotTimeoutSeconds = 300;

    public boolean isDailyEnabled() { return dailyEnabled; }
    public void setDailyEnabled(boolean dailyEnabled) { this.dailyEnabled = dailyEnabled; }
    public String getDailyCron() { return dailyCron; }
    public void setDailyCron(String dailyCron) { this.dailyCron = dailyCron; }
    public boolean isWeeklyEnabled() { return weeklyEnabled; }
    public void setWeeklyEnabled(boolean weeklyEnabled) { this.weeklyEnabled = weeklyEnabled; }
    public String getWeeklyCron() { return weeklyCron; }
    public void setWeeklyCron(String weeklyCron) { this.weeklyCron = weeklyCron; }
    public int getGitScanMaxCommits() { return gitScanMaxCommits; }
    public void setGitScanMaxCommits(int gitScanMaxCommits) { this.gitScanMaxCommits = gitScanMaxCommits; }
    public int getOneshotTimeoutSeconds() { return oneshotTimeoutSeconds; }
    public void setOneshotTimeoutSeconds(int oneshotTimeoutSeconds) { this.oneshotTimeoutSeconds = oneshotTimeoutSeconds; }
}
