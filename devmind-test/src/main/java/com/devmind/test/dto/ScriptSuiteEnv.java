package com.devmind.test.dto;

/**
 * CAP-69 脚本套件 env 条目。secret=true 的值在视图层掩码；写回时 value=掩码 表示该条不变。
 * （Jackson 3 红线：布尔字段必须用 Boolean 包装。）
 */
public record ScriptSuiteEnv(String key, String value, Boolean secret) {

    /** 视图层掩码值（PUT 回传此值 = 该条值不变） */
    public static final String MASK = "******";

    public boolean isSecret() {
        return Boolean.TRUE.equals(secret);
    }
}
