package com.devmind.serveradapter.config;

import com.devmind.common.security.ServerCredentialCipher;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CAP-07 FR-07 凭证加密：AES-256/GCM，敏感字段密文落库，格式 {@code enc1:base64(iv||ciphertext||tag)}。
 * 密钥来源：devmind.server-adapter.crypto-key；留空则自动生成并写入 data/server-crypto.key（gitignored），
 * 重启不失效。实现对重复加密幂等（已是 enc1: 前缀不再加密）。
 */
@Component
public class CredentialCrypto implements ServerCredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCrypto.class);
    private static final String PREFIX = "enc1:";
    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 16;
    private static final Set<String> SENSITIVE_KEYS = new LinkedHashSet<>(java.util.List.of(
            "password", "privateKey", "passphrase", "token", "secret", "sshKey",
            "apiKey", "accessKey", "secretKey", "pem"));

    private final ServerAdapterProperties props;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private byte[] key;

    public CredentialCrypto(ServerAdapterProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
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
                // 非 base64 视为原始字节串
                return cfg.getBytes(StandardCharsets.UTF_8);
            }
        }
        Path file = Path.of("data", "server-crypto.key");
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
            log.info("已自动生成凭证加密密钥: {}（本机专属，勿提交）", file.toAbsolutePath());
            return gen;
        } catch (Exception e) {
            log.warn("无法持久化加密密钥，回退为随机密钥（重启后旧密文将无法解密）: {}", e.toString());
            byte[] gen = new byte[32];
            random.nextBytes(gen);
            return gen;
        }
    }

    private byte[] aesKey() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(key);
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
            throw new IllegalStateException("凭证加密失败: " + e.getMessage(), e);
        }
    }

    public String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return value; // 明文或旧数据原样返回
        }
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, IV_LEN);
            byte[] ct = java.util.Arrays.copyOfRange(all, IV_LEN, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey(), "AES"), new GCMParameterSpec(TAG_LEN * 8, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败（密钥变更/损坏）：返回原值避免直接炸掉读接口，但记录告警
            log.warn("凭证解密失败（原值保留，可能无法连接）: {}", e.getMessage());
            return value;
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    @Override
    public String encryptConfigJson(String accessConfigJson) {
        return process(accessConfigJson, true);
    }

    @Override
    public String decryptConfigJson(String accessConfigJson) {
        return process(accessConfigJson, false);
    }

    private String process(String json, boolean encrypting) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isObject()) {
                return json;
            }
            walk((ObjectNode) node, encrypting);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("accessConfig JSON 处理失败（原样返回）: {}", e.getMessage());
            return json;
        }
    }

    private void walk(ObjectNode node, boolean encrypting) {
        for (java.util.Map.Entry<String, JsonNode> entry : node.properties()) {
            String field = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                walk((ObjectNode) value, encrypting);
            } else if (value.isTextual() && SENSITIVE_KEYS.contains(field)) {
                String raw = value.asText();
                String out = encrypting ? (isEncrypted(raw) ? raw : encrypt(raw))
                                        : (isEncrypted(raw) ? decrypt(raw) : raw);
                node.put(field, out);
            }
        }
    }
}
