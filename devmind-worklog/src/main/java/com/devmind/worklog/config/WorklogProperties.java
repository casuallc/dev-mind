package com.devmind.worklog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-28 工时管理配置（devmind.worklog.*）。主类 @ConfigurationPropertiesScan 全局扫描，无需注册。
 */
@ConfigurationProperties(prefix = "devmind.worklog")
public class WorklogProperties {

    /** 每日定时生成日报草稿开关 */
    private boolean dailyEnabled = true;

    /** 日报生成全局 cron（默认每日 18:30；用户可在设置页配个人执行时间覆盖，见 WorklogSchedule） */
    private String dailyCron = "0 30 18 * * *";

    /** 每周定时生成周报草稿开关 */
    private boolean weeklyEnabled = true;

    /** 周报生成全局 cron（默认周一 09:00，汇总上一周；个人需星期+时间同时设置才覆盖） */
    private String weeklyCron = "0 0 9 * * MON";

    /** 每日定时从 Git 导入工作条目总开关（CAP-28 FR-09；用户级 auto_git_import 仍需各自打开） */
    private boolean gitImportEnabled = true;

    /** Git 定时导入全局 cron（默认每日 18:00，早于日报生成的 18:30，当日条目先落库再进日报素材） */
    private String gitImportCron = "0 0 18 * * *";

    /** 单库单日 git log 扫描上限 */
    private int gitScanMaxCommits = 200;

    public boolean isDailyEnabled() { return dailyEnabled; }
    public void setDailyEnabled(boolean dailyEnabled) { this.dailyEnabled = dailyEnabled; }
    public String getDailyCron() { return dailyCron; }
    public void setDailyCron(String dailyCron) { this.dailyCron = dailyCron; }
    public boolean isWeeklyEnabled() { return weeklyEnabled; }
    public void setWeeklyEnabled(boolean weeklyEnabled) { this.weeklyEnabled = weeklyEnabled; }
    public String getWeeklyCron() { return weeklyCron; }
    public void setWeeklyCron(String weeklyCron) { this.weeklyCron = weeklyCron; }
    public boolean isGitImportEnabled() { return gitImportEnabled; }
    public void setGitImportEnabled(boolean gitImportEnabled) { this.gitImportEnabled = gitImportEnabled; }
    public String getGitImportCron() { return gitImportCron; }
    public void setGitImportCron(String gitImportCron) { this.gitImportCron = gitImportCron; }
    public int getGitScanMaxCommits() { return gitScanMaxCommits; }
    public void setGitScanMaxCommits(int gitScanMaxCommits) { this.gitScanMaxCommits = gitScanMaxCommits; }
}
