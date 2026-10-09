package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WorklogSchedule 两级调度解析：个人时间覆盖/全局兜底 + 分钟级到期判定 + 展示标签。 */
class WorklogScheduleTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 3, 0, ZONE);
    }

    // ---- normalizeTime ----

    @Test
    void normalizeTimePadsHourAndRejectsGarbage() {
        assertEquals("08:05", WorklogSchedule.normalizeTime("8:05"));
        assertEquals("18:30", WorklogSchedule.normalizeTime(" 18:30 "));
        assertEquals("00:00", WorklogSchedule.normalizeTime("0:00"));
        assertThrows(DevMindException.class, () -> WorklogSchedule.normalizeTime("24:00"));
        assertThrows(DevMindException.class, () -> WorklogSchedule.normalizeTime("18:60"));
        assertThrows(DevMindException.class, () -> WorklogSchedule.normalizeTime("abc"));
    }

    @Test
    void validateWeeklyDayBounds() {
        assertEquals(1, WorklogSchedule.validateWeeklyDay(1));
        assertEquals(7, WorklogSchedule.validateWeeklyDay(7));
        assertThrows(DevMindException.class, () -> WorklogSchedule.validateWeeklyDay(0));
        assertThrows(DevMindException.class, () -> WorklogSchedule.validateWeeklyDay(8));
    }

    // ---- resolveDaily / resolveWeekly ----

    @Test
    void dailyUserTimeOverridesGlobal() {
        CronExpression global = CronExpression.parse("0 30 18 * * *");
        assertSame(global, WorklogSchedule.resolveDaily(null, global));
        assertSame(global, WorklogSchedule.resolveDaily(" ", global));
        CronExpression user = WorklogSchedule.resolveDaily("07:15", global);
        assertTrue(WorklogSchedule.dueThisMinute(user, at(2026, 10, 9, 7, 15)));
        assertFalse(WorklogSchedule.dueThisMinute(user, at(2026, 10, 9, 18, 30)));
    }

    @Test
    void weeklyOverrideRequiresBothDayAndTime() {
        CronExpression global = CronExpression.parse("0 0 9 * * MON");
        assertSame(global, WorklogSchedule.resolveWeekly(null, null, global));
        assertSame(global, WorklogSchedule.resolveWeekly("10:00", null, global));   // 只设时间 → 跟随全局
        assertSame(global, WorklogSchedule.resolveWeekly(null, 3, global));          // 只设星期 → 跟随全局
        CronExpression user = WorklogSchedule.resolveWeekly("10:00", 3, global);
        // 2026-10-07 是周三，2026-10-08 周四、2026-10-12 周一
        assertTrue(WorklogSchedule.dueThisMinute(user, at(2026, 10, 7, 10, 0)));
        assertFalse(WorklogSchedule.dueThisMinute(user, at(2026, 10, 8, 10, 0)));
        assertFalse(WorklogSchedule.dueThisMinute(user, at(2026, 10, 12, 9, 0)));
    }

    // ---- dueThisMinute ----

    @Test
    void dueOnlyAtScheduledMinute() {
        CronExpression daily = CronExpression.parse("0 30 18 * * *");
        assertTrue(WorklogSchedule.dueThisMinute(daily, at(2026, 10, 9, 18, 30)));
        assertFalse(WorklogSchedule.dueThisMinute(daily, at(2026, 10, 9, 18, 29)));
        assertFalse(WorklogSchedule.dueThisMinute(daily, at(2026, 10, 9, 18, 31)));
    }

    @Test
    void subMinuteCronCollapsesToOncePerMinute() {
        // E2E 常用的 */15s：分钟粒度主 tick 下每分钟到期一次
        CronExpression fast = CronExpression.parse("*/15 * * * * *");
        assertTrue(WorklogSchedule.dueThisMinute(fast, at(2026, 10, 9, 18, 0)));
        assertTrue(WorklogSchedule.dueThisMinute(fast, at(2026, 10, 9, 18, 1)));
    }

    @Test
    void weeklyCronOnlyDueOnMatchingDay() {
        CronExpression mon9 = CronExpression.parse("0 0 9 * * MON");
        // 2026-10-12 周一、2026-10-13 周二
        assertTrue(WorklogSchedule.dueThisMinute(mon9, at(2026, 10, 12, 9, 0)));
        assertFalse(WorklogSchedule.dueThisMinute(mon9, at(2026, 10, 13, 9, 0)));
    }

    // ---- friendlyLabel ----

    @Test
    void friendlyLabelCoversSimpleCronsAndFallsBackToRaw() {
        assertEquals("每天 18:30", WorklogSchedule.friendlyLabel("0 30 18 * * *"));
        assertEquals("每天 09:00", WorklogSchedule.friendlyLabel("0 0 9 * * *"));
        assertEquals("每周一 09:00", WorklogSchedule.friendlyLabel("0 0 9 * * MON"));
        assertEquals("每周日 09:00", WorklogSchedule.friendlyLabel("0 0 9 * * SUN"));
        assertEquals("每周三 10:05", WorklogSchedule.friendlyLabel("0 5 10 * * WED"));
        assertEquals("cron */15 * * * * *", WorklogSchedule.friendlyLabel("*/15 * * * * *"));
        assertEquals("cron 0 */10 9-17 * * MON-FRI", WorklogSchedule.friendlyLabel("0 */10 9-17 * * MON-FRI"));
    }
}
