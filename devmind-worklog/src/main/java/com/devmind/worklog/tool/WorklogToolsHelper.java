package com.devmind.worklog.tool;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/** 工作日志工具共享小助手（CAP-76 M6/M9）：日期解析、工时格式化与数值参数读取。 */
final class WorklogToolsHelper {

    private WorklogToolsHelper() {
    }

    static LocalDate dateOr(String raw, LocalDate def) {
        if (raw == null) {
            return def;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd: " + raw);
        }
    }

    /** 可选 Long 参数（id 类）；非数字/缺失返回 null，由调用方决定缺省或报错。 */
    static Long longArg(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v instanceof Number n ? n.longValue() : null;
    }

    /** 可选工时参数（小时，0~24）；缺失返回 null，越界抛 IllegalArgumentException。 */
    static Double hoursArg(Map<String, Object> args) {
        Object v = args == null ? null : args.get("hours");
        if (v == null) {
            return null;
        }
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("工时必须为数字（小时）");
        }
        double h = n.doubleValue();
        if (h < 0 || h > 24) {
            throw new IllegalArgumentException("工时必须为 0~24 小时");
        }
        return h;
    }

    static String hours(Double h) {
        return h == null ? "0" : hours(h.doubleValue());
    }

    static String hours(double h) {
        String s = String.format(Locale.ROOT, "%.2f", h);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
