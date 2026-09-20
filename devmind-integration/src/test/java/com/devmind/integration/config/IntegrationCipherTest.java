package com.devmind.integration.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-48 FR-02 兼容红线：{@link IntegrationCipher} 的加解密实现虽然抽到了
 * {@code SecretCipher}，但**存量密文必须仍可解**——CAP-18 已入库的 token 依赖
 * 域分隔串与密钥派生方式一字不变。
 *
 * <p>本测试内联了一份"历史算法"的参考实现（域分隔串字面量、SHA-256 派生、enc1 布局），
 * 用它加密，再用改造后的类解密。任何人改动域分隔串或派生方式，这里立刻红。</p>
 */
class IntegrationCipherTest {

    private static final String KEY = "devmind-test-master-key";
    /** 历史域分隔串字面量——**故意不引用生产常量**，否则改常量这里跟着改就测不出回归 */
    private static final String HISTORIC_DOMAIN = "devmind-integration";

    /** 历史算法的参考实现（复制自 CAP-18 IntegrationCipher 改造前版本） */
    private static String legacyEncrypt(String plaintext) throws Exception {
        byte[] iv = new byte[12];
        java.util.Arrays.fill(iv, (byte) 0x11);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(KEY.getBytes(StandardCharsets.UTF_8));
        byte[] key = md.digest(HISTORIC_DOMAIN.getBytes(StandardCharsets.UTF_8));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return "enc1:" + Base64.getEncoder().encodeToString(out);
    }

    private static IntegrationCipher cipher() {
        IntegrationProperties props = new IntegrationProperties();
        props.setCryptoKey(KEY);
        IntegrationCipher c = new IntegrationCipher(props);
        c.init();
        return c;
    }

    @Test
    void decryptsCiphertextProducedByLegacyAlgorithm() throws Exception {
        String legacy = legacyEncrypt("glpat-legacy-token");
        assertEquals("glpat-legacy-token", cipher().decrypt(legacy),
                "域分隔串/密钥派生/密文布局任一改动都会让 CAP-18 存量 token 解不开");
    }

    @Test
    void roundTripAndIdempotentPrefix() {
        IntegrationCipher c = cipher();
        String enc = c.encrypt("secret-value");
        assertTrue(enc.startsWith("enc1:"));
        assertTrue(c.isEncrypted(enc));
        assertEquals("secret-value", c.decrypt(enc));
        assertEquals("secret-value", c.decrypt("secret-value"), "非密文原样返回（兼容旧明文数据）");
    }

    @Test
    void basicAuthSecretKeepsNewlineEncoding() {
        IntegrationCipher c = cipher();
        assertEquals("user\npass", c.decrypt(c.encrypt("user\npass")));
    }

    @Test
    void blankAndNullPassThrough() {
        IntegrationCipher c = cipher();
        assertEquals("", c.encrypt(""));
        assertFalse(c.isEncrypted(null));
        assertNull(c.decrypt(null));
    }
}
