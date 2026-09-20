package com.devmind.common.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CAP-48 FR-02 通用凭据加密：AES-256/GCM，密文格式 {@code enc1:base64(iv|ciphertext|tag)}
 * （沿用 CAP-07/18 约定）。每个消费方一个"域"——域分隔串参与密钥派生，决定密文可解性。
 *
 * <p><b>兼容红线</b>：{@code enc1:} 前缀与各域分隔串一经发布不得更改，否则存量密文全部解不开。
 * {@code devmind-integration} 域的字面值必须永远保持历史值（CAP-18 存量 token 依赖它）。</p>
 *
 * <p>密钥来源链（与 CAP-18 原实现完全一致，取先命中者）：
 * 显式配置密钥 → {@code data/auth.key} → 自动生成 {@code data/<fallbackKeyFile>}（本机专属，勿提交）。
 * 派生的 AES 密钥 = SHA-256(master || domain)。</p>
 */
public final class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String PREFIX = "enc1:";
    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 16;

    private final String domain;
    private final byte[] aesKey;
    private final SecureRandom random = new SecureRandom();

    private SecretCipher(String domain, byte[] aesKey) {
        this.domain = domain;
        this.aesKey = aesKey;
    }

    /**
     * 创建指定域的凭据加解密器。
     *
     * @param configKey       显式配置密钥（base64 或原始串）；空 = 走文件链
     * @param domain          域分隔串（发布后禁改，否则存量密文解不开）
     * @param fallbackKeyFile 自动生成密钥的落点文件名（相对 {@code data/}）
     */
    public static SecretCipher create(String configKey, String domain, String fallbackKeyFile) {
        byte[] master = loadMasterKey(configKey, domain, fallbackKeyFile);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(master);
            return new SecretCipher(domain, md.digest(domain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密密钥派生失败: " + e.getMessage(), e);
        }
    }

    private static byte[] loadMasterKey(String configKey, String domain, String fallbackKeyFile) {
        if (configKey != null && !configKey.isBlank()) {
            try {
                return Base64.getDecoder().decode(configKey.trim());
            } catch (IllegalArgumentException e) {
                return configKey.getBytes(StandardCharsets.UTF_8);
            }
        }
        // 复用 CAP-01 的 data/auth.key（文档口径：同一把本机密钥，域分隔派生）
        Path authKey = Path.of("data", "auth.key");
        try {
            if (Files.exists(authKey)) {
                byte[] raw = Files.readAllBytes(authKey);
                if (raw.length >= 16) {
                    log.info("凭据加密密钥（域 {}）：由 data/auth.key 派生", domain);
                    return raw;
                }
            }
        } catch (Exception e) {
            log.warn("读取 data/auth.key 失败，回退独立密钥文件: {}", e.getMessage());
        }
        Path file = Path.of("data", fallbackKeyFile);
        try {
            if (Files.exists(file)) {
                byte[] b = Files.readAllBytes(file);
                if (b.length >= 16) {
                    return b;
                }
            }
            byte[] gen = new byte[32];
            SecureRandom rnd = new SecureRandom();
            rnd.nextBytes(gen);
            Files.createDirectories(file.getParent());
            Files.write(file, gen);
            log.info("已自动生成凭据加密密钥: {}（本机专属，勿提交）", file.toAbsolutePath());
            return gen;
        } catch (Exception e) {
            log.warn("无法持久化加密密钥，回退为随机密钥（重启后旧密文将无法解密）: {}", e.toString());
            byte[] gen = new byte[32];
            new SecureRandom().nextBytes(gen);
            return gen;
        }
    }

    /** 空串原样返回（无可加密内容，避免把空串变成密文） */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(TAG_LEN * 8, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密；非 enc1: 前缀视为明文原样返回（兼容旧数据）。解密失败抛异常（凭据场景宁可失败不可用） */
    public String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return value;
        }
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(all, IV_LEN, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(TAG_LEN * 8, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("凭据解密失败（密钥变更或密文损坏）", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** 本实例所属域（诊断用，不含密钥） */
    public String domain() {
        return domain;
    }
}
