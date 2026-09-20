package com.devmind.integration.config;

import com.devmind.common.crypto.SecretCipher;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * CAP-18 FR-01 凭据加密：AES-256/GCM，格式 {@code enc1:base64(iv||ciphertext||tag)}（同 CAP-07 约定）。
 * 密钥优先级：devmind.integration.crypto-key 配置 → data/auth.key 派生 → 自动生成
 * data/integration-crypto.key（gitignored）。对重复加密幂等。
 *
 * <p>CAP-48 FR-02 起加解密实现抽取到 {@link SecretCipher}（模型端点等其他凭据场景复用同一套密钥来源，
 * 避免出现两套密钥导致"数据在但解不开"）。本类保留 bean 名与全部既有行为，
 * <b>域分隔串 {@value #DOMAIN} 必须保持历史字面值</b>——CAP-48 有单测钉死存量密文仍可解。</p>
 */
@Component
public class IntegrationCipher {

    /** 域分隔串（发布后禁改；改则 CAP-18 存量 token 全部解不开） */
    static final String DOMAIN = "devmind-integration";

    private final IntegrationProperties props;
    private SecretCipher cipher;

    public IntegrationCipher(IntegrationProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        this.cipher = SecretCipher.create(props.getCryptoKey(), DOMAIN, "integration-crypto.key");
    }

    public String encrypt(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    /** 解密；非 enc1: 前缀视为明文原样返回（兼容旧数据）。解密失败抛异常（凭据场景宁可失败不可用） */
    public String decrypt(String value) {
        return cipher.decrypt(value);
    }

    public boolean isEncrypted(String value) {
        return cipher.isEncrypted(value);
    }
}
