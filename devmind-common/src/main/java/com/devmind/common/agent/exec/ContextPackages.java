package com.devmind.common.agent.exec;

import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * {@link ContextPackage} 的 JSON 序列化与完整性摘要。服务端与 runner 共用同一实现，
 * 保证「服务端 sha256 的字节流」与「runner 校验的字节流」是同一规范形态。
 */
public final class ContextPackages {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ContextPackages() {
    }

    public static byte[] toJsonBytes(ContextPackage pkg) {
        return MAPPER.writeValueAsBytes(pkg);
    }

    public static ContextPackage fromJson(byte[] bytes) {
        return MAPPER.readValue(bytes, ContextPackage.class);
    }

    /** 对包 JSON 字节流计算清单（entries 由装配方统计：知识条目 + skills + docs）。 */
    public static ContextManifest manifestOf(byte[] jsonBytes, int entries) {
        return new ContextManifest(entries, jsonBytes.length, sha256Hex(jsonBytes));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
