package com.devmind.test.executor;

import com.devmind.common.program.ProgramContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-104 附录 C 输出解析：分片还原、sha 校验、契约字段、退出码映射。 */
class ProgramResultParserTest {

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> resultLines(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        lines.add(ProgramContract.RESULT_MARKER + bytes.length + " " + ProgramResultParser.sha256(bytes));
        int seq = 0;
        for (int off = 0; off < bytes.length; off += 10) {
            int end = Math.min(bytes.length, off + 10);
            lines.add(ProgramContract.RESULT_CHUNK_MARKER + seq++ + " "
                    + b64(java.util.Arrays.copyOfRange(bytes, off, end)));
        }
        return lines;
    }

    @Test
    void collectsChunkedResultAndArtifacts() {
        List<String> lines = resultLines("""
                {"contractVersion":"1","status":"pass"}
                """);
        byte[] artifact = "hello".getBytes(StandardCharsets.UTF_8);
        String name = b64("logs/app.log");
        lines.add(ProgramContract.ARTIFACT_MARKER + name + " " + artifact.length + " "
                + ProgramResultParser.sha256(artifact));
        lines.add(ProgramContract.ARTIFACT_CHUNK_MARKER + name + " 0 " + b64(artifact));

        ProgramResultParser.Collected c = ProgramResultParser.collect(lines);
        assertTrue(new String(c.resultJson(), StandardCharsets.UTF_8).contains("\"pass\""));
        assertEquals(1, c.artifacts().size());
        assertEquals("logs/app.log", c.artifacts().get(0).path());
        assertEquals("hello", new String(c.artifacts().get(0).content(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsArtifactPathTraversal() {
        byte[] content = new byte[]{1};
        String name = b64("../escape.txt");
        List<String> lines = List.of(
                ProgramContract.ARTIFACT_MARKER + name + " 1 " + ProgramResultParser.sha256(content),
                ProgramContract.ARTIFACT_CHUNK_MARKER + name + " 0 " + b64(content));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProgramResultParser.collect(lines));
        assertTrue(e.getMessage().contains("相对路径"));
    }

    @Test
    void rejectsArtifactShaMismatch() {
        byte[] content = new byte[]{1, 2};
        String name = b64("a.bin");
        List<String> lines = List.of(
                ProgramContract.ARTIFACT_MARKER + name + " 2 deadbeef",
                ProgramContract.ARTIFACT_CHUNK_MARKER + name + " 0 " + b64(content));
        assertThrows(IllegalArgumentException.class, () -> ProgramResultParser.collect(lines));
    }

    @Test
    void rejectsResultShaMismatch() {
        List<String> lines = new ArrayList<>(resultLines("""
                {"contractVersion":"1","status":"pass"}
                """));
        lines.set(0, ProgramContract.RESULT_MARKER + "999 deadbeef");
        assertThrows(IllegalArgumentException.class, () -> ProgramResultParser.collect(lines));
    }

    @Test
    void decodesPassWithAssertionsAndMetrics() {
        byte[] json = """
                {
                  "contractVersion":"1",
                  "status":"pass",
                  "message":"ok",
                  "assertions":[{"name":"msg-sent","expected":"true","actual":"true","pass":true}],
                  "metrics":{"latencyMs":12,"throughput":3.5}
                }
                """.getBytes(StandardCharsets.UTF_8);
        ProgramResultParser.Decoded d = ProgramResultParser.decode(json);
        assertEquals("pass", d.status());
        assertEquals(1, d.assertions().size());
        assertEquals("CUSTOM", d.assertions().get(0).source());
        assertEquals(12L, d.metrics().get("latencyMs"));
        assertEquals(3.5, d.metrics().get("throughput"));
    }

    @Test
    void rejectsMissingContractVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> ProgramResultParser.decode("{\"status\":\"pass\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsUnknownStatus() {
        assertThrows(IllegalArgumentException.class, () -> ProgramResultParser.decode(
                "{\"contractVersion\":\"1\",\"status\":\"green\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsNonNumericMetrics() {
        assertThrows(IllegalArgumentException.class, () -> ProgramResultParser.decode(
                "{\"contractVersion\":\"1\",\"status\":\"pass\",\"metrics\":{\"x\":\"y\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMalformedAssertions() {
        assertThrows(IllegalArgumentException.class, () -> ProgramResultParser.decode(
                "{\"contractVersion\":\"1\",\"status\":\"fail\",\"assertions\":[{\"expected\":\"a\"}]}"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void exitCodesMapToStatusAndCategory() {
        assertEquals("fail", CaseExecutionService.exitStatus(1));
        assertEquals("ASSERTION", CaseExecutionService.exitCategory(1));
        assertEquals("blocked", CaseExecutionService.exitStatus(2));
        assertEquals("PRECHECK", CaseExecutionService.exitCategory(2));
        assertEquals("error", CaseExecutionService.exitStatus(3));
        assertEquals("SCRIPT_ERROR", CaseExecutionService.exitCategory(3));
        assertEquals("error", CaseExecutionService.exitStatus(127));
        assertEquals("UNKNOWN", CaseExecutionService.exitCategory(127));
    }
}
