package com.devmind.security.engine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-66 §7 脱敏红线：密钥命中行落库前打码（只留前后各 4 字符，短串全码），
 * detail 全文不得出现密钥原文；fingerprint 基于脱敏后内容（无可逆穷举面）。
 */
class SecretsEngineAdapterTest {

    private final SecretsEngineAdapter adapter = new SecretsEngineAdapter(new ObjectMapper());

    @Test
    void mask只留前后各四字符() {
        assertEquals("AKIA****1234", SecretsEngineAdapter.mask("AKIAIOSFODNN7EXAMPLE1234"));
        assertEquals("********", SecretsEngineAdapter.mask("short12"));
        assertEquals("********", SecretsEngineAdapter.mask("12345678"));
        assertNull(SecretsEngineAdapter.mask(null));
    }

    @Test
    void parse后落库内容不含密钥原文() {
        String secret = "AKIAIOSFODNN7EXAMPLE1234567890";
        String sarif = """
                {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"gitleaks","rules":[]}},
                  "results":[{"ruleId":"aws-access-key","level":"error",
                    "message":{"text":"AWS Access Key detected"},
                    "locations":[{"physicalLocation":{
                       "artifactLocation":{"uri":"config/app.yml"},
                       "region":{"startLine":7,"snippet":{"text":"key: %s"}}}}]}]}]}"""
                .formatted(secret);
        List<NormalizedFinding> out = adapter.parse(sarif.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, out.size());
        NormalizedFinding f = out.get(0);
        assertFalse(f.lineContent().contains(secret.substring(4, secret.length() - 4)));
        assertTrue(f.lineContent().startsWith("key:"));
        // detail 序列化后同样无原文
        String detail = new ObjectMapper().writeValueAsString(f.detail());
        assertFalse(detail.contains(secret.substring(4, secret.length() - 4)));
        assertEquals("secrets", f.engine());
        assertEquals("HIGH", f.severity());
        // fingerprint 稳定且不含明文
        String fp = adapter.fingerprint(f);
        assertEquals(40, fp.length());
        assertFalse(fp.contains(secret));
    }

    @Test
    void 空SARIF兜底解析为零发现() {
        List<NormalizedFinding> out = adapter.parse(
                SecretsEngineAdapter.EMPTY_SARIF.getBytes(StandardCharsets.UTF_8));
        assertTrue(out.isEmpty());
    }
}
