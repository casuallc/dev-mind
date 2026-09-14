package com.devmind.worklog.dto;

import com.devmind.worklog.model.WorklogUserSettingsEntity;

/**
 * 个人设置视图。dailyTemplateMd/weeklyTemplateMd 为 null = 用内置默认模板
 * （默认模板内容走 GET /worklog/settings/templates/default 获取）。
 */
public record SettingsView(Boolean autoDaily, Boolean autoWeekly, Integer dailyMinutesTarget,
                           String dailyTemplateMd, String weeklyTemplateMd) {

    /** 无设置行时的默认视图（默认开启，模板走内置）。 */
    public static SettingsView defaults() {
        return new SettingsView(Boolean.TRUE, Boolean.TRUE, null, null, null);
    }

    public static SettingsView of(WorklogUserSettingsEntity e) {
        return new SettingsView(e.getAutoDaily(), e.getAutoWeekly(), e.getDailyMinutesTarget(),
                e.getDailyTemplateMd(), e.getWeeklyTemplateMd());
    }
}
