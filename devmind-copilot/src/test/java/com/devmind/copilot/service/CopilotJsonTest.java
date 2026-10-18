package com.devmind.copilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CAP-76 FR-28 / 验收 7：审计副本掩码与自由文本脱敏。 */
class CopilotJsonTest {

    @Test
    void masksSecretKeysRecursively() {
        String json = CopilotJson.maskedArgsJson(Map.of(
                "projectId", "p1",
                "apiKey", "sk-abcdefghijkl",
                "nested", Map.of("password", "p@ss", "note", "keep"),
                "items", List.of(Map.of("token", "t0ken", "name", "n"))), 2000);

        assertTrue(json.contains("\"projectId\":\"p1\""));
        assertTrue(json.contains("\"apiKey\":\"***\""));
        assertTrue(json.contains("\"password\":\"***\""));
        assertTrue(json.contains("\"token\":\"***\""));
        assertTrue(json.contains("\"note\":\"keep\""));
        assertFalse(json.contains("sk-abcdefghijkl"));
        assertFalse(json.contains("p@ss"));
    }

    @Test
    void truncatesLongStrings() {
        String json = CopilotJson.maskedArgsJson(Map.of("text", "0123456789"), 5);
        assertTrue(json.contains("01234…"));
    }

    @Test
    void masksFreeTextSecrets() {
        assertEquals("key=***", CopilotJson.maskText("key=sk-abcdefghijkl"));
        assertEquals("auth *** end", CopilotJson.maskText("auth Bearer abcdefgh.ijkl end"));
        assertEquals("普通文本", CopilotJson.maskText("普通文本"));
    }

    @Test
    void readMapToleratesBrokenJson() {
        assertEquals(Map.of(), CopilotJson.readMap(null));
        assertEquals(Map.of(), CopilotJson.readMap("not json"));
        assertEquals("v", CopilotJson.readMap("{\"k\":\"v\"}").get("k"));
    }
}
