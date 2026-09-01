package com.devmind.integration.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * CAP-18 FR-01 凭据加密：AES-256/GCM，格式 {@code enc1:base64(iv||ciphertext||tag)}（同 CAP-07 约定）。
 * 密钥优先级：devmind.integration.crypto-key 配置 → data/auth.key 派生（SHA-256(key || "devmind-integration")）
 * → 自动生成 data/integration-crypto.key（gitignored）。对重复加密幂等。
 */
@Component
public class IntegrationCipher {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCipher.class);
    private static final String PREFIX = "enc1:";
    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 16;

    private final IntegrationProperties props;
    private final SecureRandom random = new SecureRandom();
    private byte[] key;

    public IntegrationCipher(IntegrationProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        this.key = loadKey();
    }

    private byte[] loadKey() {
        String cfg = props.getCryptoKey();
        if (cfg != null && !cfg.isBlank()) {
            try {
                return Base64.getDecoder().decode(cfg.trim());
            } catch (IllegalArgumentException e) {
                return cfg.getBytes(StandardCharsets.UTF_8);
            }
        }
        // 复用 CAP-01 的 data/auth.key 派生（文档口径：同一把本机密钥，域分隔派生）
        Path authKey = Path.of("data", "auth.key");
        try {
            if (Files.exists(authKey)) {
                byte[] raw = Files.readAllBytes(authKey);
                if (raw.length >= 16) {
                    log.info("集成凭据加密密钥：由 data/auth.key 派生");
                    return raw;
                }
            }
        } catch (Exception e) {
            log.warn("读取 data/auth.key 失败，回退独立密钥文件: {}", e.getMessage());
        }
        Path file = Path.of("data", "integration-crypto.key");
        try {
            if (Files.exists(file)) {
                byte[] b = Files.readAllBytes(file);
                if (b.length >= 16) {
                    return b;
                }
            }
            byte[] gen = new byte[32];
            random.nextBytes(gen);
            Files.createDirectories(file.getParent());
            Files.write(file, gen);
            log.info("已自动生成集成凭据加密密钥: {}（本机专属，勿提交）", file.toAbsolutePath());
            return gen;
        } catch (Exception e) {
            log.warn("无法持久化加密密钥，回退为随机密钥（重启后旧密文将无法解密）: {}", e.toString());
            byte[] gen = new byte[32];
            random.nextBytes(gen);
            return gen;
        }
    }

    /** 域分隔派生 AES-256 密钥：SHA-256(master || "devmind-integration") */
    private byte[] aesKey() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(key);
        return md.digest("devmind-integration".getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey(), "AES"), new GCMParameterSpec(TAG_LEN * 8, iv));
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
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, IV_LEN);
            byte[] ct = java.util.Arrays.copyOfRange(all, IV_LEN, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey(), "AES"), new GCMParameterSpec(TAG_LEN * 8, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("凭据解密失败（密钥变更或密文损坏）", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
