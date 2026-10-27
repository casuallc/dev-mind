package com.devmind.worklog.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;

/**
 * ISO-8601 周工具（周一开始、含 1 月 4 日的周为第 1 周）。
 * CAP-89：原属 WorklogRepoService 的静态方法随仓服务删除独立出来——成稿回传解析
 * weekly-yyyy-Www 文件名、存量仓迁移定位周报行共用。CAP-76 M9：public 化供 tool 包归一周起点。
 */
public final class WorklogWeeks {

    private WorklogWeeks() {
    }

    /** ISO 年/周 → 该周周一的日期。 */
    static LocalDate weekStartOf(int year, int week) {
        return LocalDate.of(year, 1, 4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(DayOfWeek.MONDAY);
    }

    /** 任意日期 → 所在周周一（CAP-88 周计划 plan_date 归一用）。 */
    public static LocalDate mondayOf(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }

    /** 周一开始日期 → "yyyy-Www"（ISO 周年/周，文件名口径）。 */
    static String weekFileName(LocalDate weekStart) {
        return String.format("%04d-W%02d",
                weekStart.get(IsoFields.WEEK_BASED_YEAR),
                weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
