package com.devmind.worklog.dto;

import com.devmind.worklog.model.WorklogUserSettingsEntity;

public record SettingsView(Boolean autoDaily, Boolean autoWeekly, Integer dailyMinutesTarget) {

    /** 无设置行时的默认视图（默认开启）。 */
    public static SettingsView defaults() {
        return new SettingsView(Boolean.TRUE, Boolean.TRUE, null);
    }

    public static SettingsView of(WorklogUserSettingsEntity e) {
        return new SettingsView(e.getAutoDaily(), e.getAutoWeekly(), e.getDailyMinutesTarget());
    }
}
