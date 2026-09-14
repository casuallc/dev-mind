package com.devmind.worklog.dto;

import com.devmind.worklog.model.WorklogUserSettingsEntity;

/**
 * 个人设置视图。dailyTemplateMd/weeklyTemplateMd 为 null = 用内置默认模板
 * （默认模板内容走 GET /worklog/settings/templates/default 获取）。
 * remoteUrl 为 null = 未绑定远端备份仓库；remoteBranch 为 null/空白 = 默认 main。
 */
public record SettingsView(Boolean autoDaily, Boolean autoWeekly, Integer dailyMinutesTarget,
                           String dailyTemplateMd, String weeklyTemplateMd,
                           String remoteUrl, String remoteBranch) {

    /** 无设置行时的默认视图（默认开启，模板走内置，未绑定远端）。 */
    public static SettingsView defaults() {
        return new SettingsView(Boolean.TRUE, Boolean.TRUE, null, null, null, null, null);
    }

    public static SettingsView of(WorklogUserSettingsEntity e) {
        return new SettingsView(e.getAutoDaily(), e.getAutoWeekly(), e.getDailyMinutesTarget(),
                e.getDailyTemplateMd(), e.getWeeklyTemplateMd(), e.getRemoteUrl(), e.getRemoteBranch());
    }
}
