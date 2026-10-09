package com.devmind.worklog.dto;

import com.devmind.worklog.model.WorklogUserSettingsEntity;

/**
 * 个人设置视图。dailyTemplateMd/weeklyTemplateMd 为 null = 用内置默认模板
 * （默认模板内容走 GET /worklog/settings/templates/default 获取）。
 * remoteUrl 为 null = 未绑定远端备份仓库；remoteBranch 为 null/空白 = 默认 main。
 *
 * <p>三个定时项的个人执行时间：dailyTime/gitImportTime 为 "HH:mm" 或 null（= 跟随全局）；
 * weeklyDay（1=周一…7=周日）与 weeklyTime 需同时有值才覆盖全局。globalXxxLabel 为全局
 * 兜底规则的中文展示（如 "每天 18:30"），供前端 placeholder/extra 提示。</p>
 */
public record SettingsView(Boolean autoDaily, Boolean autoWeekly, Boolean autoGitImport,
                           Integer dailyMinutesTarget,
                           String dailyTemplateMd, String weeklyTemplateMd,
                           String remoteUrl, String remoteBranch,
                           String dailyTime, Integer weeklyDay, String weeklyTime, String gitImportTime,
                           String globalDailyLabel, String globalWeeklyLabel, String globalGitImportLabel) {

    /** 无设置行时的默认视图（日报/周报默认开启，Git 定时导入严格 opt-in 默认关，模板走内置，未绑定远端）。 */
    public static SettingsView defaults() {
        return new SettingsView(Boolean.TRUE, Boolean.TRUE, Boolean.FALSE, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public static SettingsView of(WorklogUserSettingsEntity e) {
        return new SettingsView(e.getAutoDaily(), e.getAutoWeekly(), e.getAutoGitImport(),
                e.getDailyMinutesTarget(),
                e.getDailyTemplateMd(), e.getWeeklyTemplateMd(), e.getRemoteUrl(), e.getRemoteBranch(),
                e.getDailyTime(), e.getWeeklyDay(), e.getWeeklyTime(), e.getGitImportTime(),
                null, null, null);
    }

    /** 附上全局兜底规则展示标签（service 层从 WorklogProperties 解析）。 */
    public SettingsView withGlobalLabels(String dailyLabel, String weeklyLabel, String gitImportLabel) {
        return new SettingsView(autoDaily, autoWeekly, autoGitImport, dailyMinutesTarget,
                dailyTemplateMd, weeklyTemplateMd, remoteUrl, remoteBranch,
                dailyTime, weeklyDay, weeklyTime, gitImportTime,
                dailyLabel, weeklyLabel, gitImportLabel);
    }
}
