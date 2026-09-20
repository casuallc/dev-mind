package com.devmind.model;

import com.devmind.common.crypto.SecretCipher;
import com.devmind.model.config.ModelProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * CAP-48 FR-02 模型端点凭据加解密。与 CAP-18 的 {@code IntegrationCipher} 共用
 * {@link SecretCipher} 的密钥来源链与格式，只有域分隔串不同（{@code devmind-model}）——
 * 同一把主密钥、不同域派生出互不可解的密钥，避免"数据在但解不开"的两套密钥事故。
 */
@Component
public class ModelCipher {

    static final String DOMAIN = "devmind-model";

    private final ModelProperties props;
    private SecretCipher cipher;

    public ModelCipher(ModelProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        this.cipher = SecretCipher.create(props.getCryptoKey(), DOMAIN, "model-crypto.key");
    }

    public String encrypt(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    /** 非 enc1: 前缀原样返回（兼容旧明文）；解密失败抛异常不静默 */
    public String decrypt(String value) {
        return cipher.decrypt(value);
    }

    public boolean isEncrypted(String value) {
        return cipher.isEncrypted(value);
    }
}
