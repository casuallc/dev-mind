package com.devmind.task.engine;

import java.security.SecureRandom;
import java.util.HexFormat;

/** 平台统一 id 生成口径（同 IdentityService.shortId：8 字节安全随机 hex，16 字符） */
public final class TaskIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TaskIds() {
    }

    public static String newId() {
        byte[] raw = new byte[8];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }
}
