package com.devmind.copilot.service;

import java.security.SecureRandom;

/**
 * CAP-76 FR-13：32 字符 Base32 随机 id（禁止 {@code UUID.toString()}，36 字符会超列宽）
 * 与 4 字符短码。
 */
public final class CopilotIds {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CopilotIds() {
    }

    /** 20 字节 = 160 bit → 32 个 Base32 字符。 */
    public static String newId() {
        return encode(20);
    }

    /** 4 字符短码（示例 A7K2）；大小写不敏感查找，统一存大写。 */
    public static String newShortCode() {
        return encode(3).substring(0, 4);
    }

    private static String encode(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder((byteCount * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        return sb.toString();
    }
}
