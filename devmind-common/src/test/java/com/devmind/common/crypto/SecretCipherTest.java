package com.devmind.common.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-48 FR-02 SecretCipher：同一把主密钥 + 不同域分隔串 → 互不可解
 * （这是"抽出来复用"之所以安全的前提），以及 enc1 格式与空值语义。
 */
class SecretCipherTest {

    private static final String KEY = "devmind-test-master-key";

    private static SecretCipher of(String domain) {
        return SecretCipher.create(KEY, domain, domain + "-crypto.key");
    }

    @Test
    void roundTripInSameDomain() {
        SecretCipher c = of("devmind-model");
        String enc = c.encrypt("sk-abc123");
        assertTrue(enc.startsWith("enc1:"));
        assertEquals("sk-abc123", c.decrypt(enc));
        assertNotEquals("sk-abc123", enc, "密文不得等于明文");
    }

    @Test
    void ciphertextIsNotPortableAcrossDomains() {
        String enc = of("devmind-integration").encrypt("shared-master");

        assertThrows(IllegalStateException.class, () -> of("devmind-model").decrypt(enc),
                "域分隔串必须参与密钥派生——否则两个场景的密文可互相解开");
    }

    @Test
    void nonCiphertextValuePassesThrough() {
        SecretCipher c = of("devmind-model");
        assertEquals("plain-token", c.decrypt("plain-token"));
        assertFalse(c.isEncrypted("plain-token"));
        assertEquals("", c.encrypt(""));
        assertNull(c.decrypt(null));
    }

    @Test
    void domainIsReportedForDiagnostics() {
        assertEquals("devmind-model", of("devmind-model").domain());
    }
}
