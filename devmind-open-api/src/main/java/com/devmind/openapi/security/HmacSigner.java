package com.devmind.openapi.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 签名规范（CAP-20，客户端 scripts/openapi.sh 与本类严格同口径）：
 * <pre>
 * stringToSign = METHOD + "\n" + path（含 query） + "\n" + timestamp + "\n" + sha256hex(body || "")
 * X-Signature  = hex(HMAC-SHA256(key = sha256hex(secret), stringToSign))
 * </pre>
 * 密钥材料是 secret 的 SHA-256 十六进制串——服务端只存该哈希，客户端本地对 sk 做同样计算，
 * secret 本身既不上网也不落库。
 */
public final class HmacSigner {

    private HmacSigner() {
    }

    public static String stringToSign(String method, String pathWithQuery, String timestamp, byte[] body) {
        return method.toUpperCase() + "\n" + pathWithQuery + "\n" + timestamp + "\n"
                + sha256Hex(body == null ? new byte[0] : body);
    }

    /** 对签名串做 HMAC-SHA256，key 为 sha256hex(secret) 字符串（UTF-8 字节） */
    public static String hmacSha256Hex(String key, String stringToSign) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 常量时间比较（hex 字符串同长度时逐字节比对，不提前返回） */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
