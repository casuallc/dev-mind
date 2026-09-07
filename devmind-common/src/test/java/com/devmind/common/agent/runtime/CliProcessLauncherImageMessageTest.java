package com.devmind.common.agent.runtime;

import com.devmind.common.agent.InputImage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-32：{@link CliProcessLauncher#buildUserMessage} 带图片附件的 stream-json 组帧断言——
 * image content blocks 在前、text 在后；纯文本行为与改造前一致。
 */
class CliProcessLauncherImageMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textOnlyKeepsOriginalShape() {
        String line = CliProcessLauncher.buildUserMessage(mapper, "你好", List.of());
        JsonNode root = mapper.readTree(line);
        assertEquals("user", root.path("type").asText());
        JsonNode content = root.path("message").path("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("你好", content.get(0).path("text").asText());
    }

    @Test
    void imageBlocksComeBeforeText() {
        String b64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        InputImage img = new InputImage("abc123def456", "a.png", "image/png", b64);
        String line = CliProcessLauncher.buildUserMessage(mapper, "看下这张图", List.of(img));
        JsonNode content = mapper.readTree(line).path("message").path("content");
        assertEquals(2, content.size());
        JsonNode image = content.get(0);
        assertEquals("image", image.path("type").asText());
        assertEquals("base64", image.path("source").path("type").asText());
        assertEquals("image/png", image.path("source").path("media_type").asText());
        assertEquals(b64, image.path("source").path("data").asText());
        assertEquals("text", content.get(1).path("type").asText());
        assertEquals("看下这张图", content.get(1).path("text").asText());
    }

    @Test
    void imageOnlyWithoutTextHasNoTextBlock() {
        InputImage img = new InputImage("abc123def456", null, "image/jpeg", "AAAA");
        String line = CliProcessLauncher.buildUserMessage(mapper, "", List.of(img));
        JsonNode content = mapper.readTree(line).path("message").path("content");
        assertEquals(1, content.size());
        assertEquals("image", content.get(0).path("type").asText());
    }

    @Test
    void emptyInputStillProducesValidContent() {
        String line = CliProcessLauncher.buildUserMessage(mapper, null, List.of());
        JsonNode content = mapper.readTree(line).path("message").path("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).path("type").asText());
    }

    @Test
    void multipleImagesAllPrecedeText() {
        InputImage a = new InputImage("id1", "a.png", "image/png", "AAAA");
        InputImage b = new InputImage("id2", "b.png", "image/png", "BBBB");
        String line = CliProcessLauncher.buildUserMessage(mapper, "对比两张图", List.of(a, b));
        JsonNode content = mapper.readTree(line).path("message").path("content");
        assertEquals(3, content.size());
        assertTrue(content.get(0).path("type").asText().equals("image")
                && content.get(1).path("type").asText().equals("image"));
        assertEquals("text", content.get(2).path("type").asText());
    }
}
