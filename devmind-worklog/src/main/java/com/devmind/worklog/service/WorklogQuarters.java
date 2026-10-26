package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * 季度周期工具（CAP-91）：period 格式校验与季度日期区间。
 * YEAR='2026'；QUARTER='2026-Q1'（Q1=1~3 月 … Q4=10~12 月）。
 */
public final class WorklogQuarters {

    private static final Pattern YEAR = Pattern.compile("^\\d{4}$");
    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-Q([1-4])$");

    private WorklogQuarters() {
    }

    /** 校验 period 与 scope 匹配（YEAR='yyyy'；QUARTER='yyyy-Qn'），不合法 400。 */
    public static String validatedPeriod(String scope, String period) {
        boolean ok = "YEAR".equals(scope) ? YEAR.matcher(period).matches()
                : QUARTER.matcher(period).matches();
        if (!ok) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "period 格式与 scope 不匹配：YEAR 应为 '2026'，QUARTER 应为 '2026-Q1'，收到: " + period);
        }
        return period;
    }

    /** period 是否为季度格式（仅格式判断，不校验 scope）。 */
    public static boolean isQuarter(String period) {
        return period != null && QUARTER.matcher(period).matches();
    }

    /** 任意日期 → 所在季度 period（如 2026-10-26 → "2026-Q4"）。 */
    public static String quarterOf(LocalDate d) {
        return d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);
    }

    /** 季度 period → [首日, 末日]（Q1=01-01~03-31 …）。 */
    public static LocalDate[] rangeOf(String quarterPeriod) {
        var m = QUARTER.matcher(quarterPeriod);
        if (!m.matches()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "季度 period 格式应为 '2026-Q1'，收到: " + quarterPeriod);
        }
        int year = Integer.parseInt(m.group(1));
        int q = Integer.parseInt(m.group(2));
        LocalDate start = LocalDate.of(year, (q - 1) * 3 + 1, 1);
        return new LocalDate[]{start, start.plusMonths(3).minusDays(1)};
    }

    /** 季度 period → 下一季（Q4 跨年：2026-Q4 → 2027-Q1）。 */
    public static String nextQuarter(String quarterPeriod) {
        LocalDate[] range = rangeOf(quarterPeriod);
        return quarterOf(range[1].plusDays(1));
    }

    /** 季度 period → 所属年份 period（2026-Q1 → "2026"）。 */
    public static String yearOf(String quarterPeriod) {
        return quarterPeriod.substring(0, 4);
    }
}
