package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-28 定时项的两级调度解析：个人执行时间（库表，"HH:mm"）优先，未设跟随全局
 * （application.yml 的 devmind.worklog.*-cron）。
 *
 * <p>调度方式：主 tick 每分钟一跳（WorklogScheduler.masterTick），逐用户解析出生效
 * {@link CronExpression}，其下一触发点落在本分钟内即到期（{@link #dueThisMinute}）。
 * 因此全局 cron 的触发节奏若细于分钟（如 {@code *&#47;15 * * * * *}）会收敛为每分钟一次，
 * 错过触发分钟（实例停机）即跳过该次，三个任务本身幂等，下一次正常触发不受影响。</p>
 */
public final class WorklogSchedule {

    private static final Pattern TIME = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");
    /** 简单整点型每日 cron：0 m H * * * */
    private static final Pattern SIMPLE_DAILY = Pattern.compile("^0 (\\d{1,2}) (\\d{1,2}) \\* \\* \\*$");
    /** 简单整点型每周 cron：0 m H * * DOW（MON..SUN 或 0-7，0/7 = 周日） */
    private static final Pattern SIMPLE_WEEKLY =
            Pattern.compile("^0 (\\d{1,2}) (\\d{1,2}) \\* \\* (MON|TUE|WED|THU|FRI|SAT|SUN|[0-7])$");
    private static final String[] DOW_NAMES = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
    private static final String[] DOW_CN = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private WorklogSchedule() {}

    /**
     * 校验并规整个人执行时间为 "HH:mm"（补零到两位小时）。非法输入抛 400。
     * 调用方负责 blank → null（清除 = 跟随全局）的语义，本方法不收空白。
     */
    public static String normalizeTime(String raw) {
        Matcher m = TIME.matcher(raw.trim());
        if (!m.matches()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "执行时间格式应为 HH:mm: " + raw);
        }
        return String.format("%02d:%s", Integer.parseInt(m.group(1)), m.group(2));
    }

    /** 校验周报执行星期：1=周一 … 7=周日，越界抛 400。 */
    public static Integer validateWeeklyDay(int day) {
        if (day < 1 || day > 7) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "周报执行星期应为 1（周一）~ 7（周日）: " + day);
        }
        return day;
    }

    /** 每日类任务（日报/Git 导入）的生效 cron：个人时间优先，空 → 全局。 */
    public static CronExpression resolveDaily(String userTime, CronExpression global) {
        if (userTime == null || userTime.isBlank()) {
            return global;
        }
        Matcher m = TIME.matcher(userTime.trim());
        if (!m.matches()) {
            return global; // 写路径已校验，存量脏数据防御性回落全局
        }
        return CronExpression.parse("0 " + m.group(2) + " " + Integer.parseInt(m.group(1)) + " * * *");
    }

    /**
     * 周报的生效 cron：星期与时间需同时设置才覆盖（无法从任意全局 cron 反拆星期/时间），
     * 任一缺失即整体跟随全局。
     */
    public static CronExpression resolveWeekly(String userTime, Integer userDay, CronExpression global) {
        if (userTime == null || userTime.isBlank() || userDay == null || userDay < 1 || userDay > 7) {
            return global;
        }
        Matcher m = TIME.matcher(userTime.trim());
        if (!m.matches()) {
            return global;
        }
        return CronExpression.parse(
                "0 " + m.group(2) + " " + Integer.parseInt(m.group(1)) + " * * " + DOW_NAMES[userDay - 1]);
    }

    /**
     * 该 cron 的下一触发点是否落在 tick 所在分钟内（含整分钟内的非 0 秒触发点，
     * 一并收敛到本 tick 执行）。
     */
    public static boolean dueThisMinute(CronExpression cron, ZonedDateTime tick) {
        ZonedDateTime minute = tick.truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime next = cron.next(minute.minusSeconds(1));
        return next != null && next.isBefore(minute.plusMinutes(1));
    }

    /** 全局 cron 的展示标签：简单整点型 → "每天 18:30" / "每周一 09:00"，复杂表达式原样展示。 */
    public static String friendlyLabel(String cron) {
        String c = cron.trim();
        Matcher daily = SIMPLE_DAILY.matcher(c);
        if (daily.matches()) {
            return "每天 " + hhmm(daily.group(2), daily.group(1));
        }
        Matcher weekly = SIMPLE_WEEKLY.matcher(c);
        if (weekly.matches()) {
            int dow = switch (weekly.group(3)) {
                case "MON" -> 1; case "TUE" -> 2; case "WED" -> 3; case "THU" -> 4;
                case "FRI" -> 5; case "SAT" -> 6; default -> 7; // SUN/0/7
            };
            return "每" + DOW_CN[dow - 1] + " " + hhmm(weekly.group(2), weekly.group(1));
        }
        return "cron " + c;
    }

    private static String hhmm(String hour, String minute) {
        return String.format("%02d:%02d", Integer.parseInt(hour), Integer.parseInt(minute));
    }
}
