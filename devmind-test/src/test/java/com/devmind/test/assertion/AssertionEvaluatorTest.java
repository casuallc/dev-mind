package com.devmind.test.assertion;

import com.devmind.test.spec.AssertionResult;
import com.devmind.test.spec.AssertionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-104 断言引擎回归：每条断言都要给出 expected/actual/pass/message。 */
class AssertionEvaluatorTest {

    private static AssertionResult one(List<AssertionResult> list) {
        assertEquals(1, list.size());
        return list.get(0);
    }

    @Test
    void emptyHttpAssertionsDefaultTo2xx() {
        AssertionResult r = one(AssertionEvaluator.evaluateHttp(null, new HttpObservation(204, Map.of(), "")));
        assertTrue(r.pass());
        assertEquals("2XX", r.expected());
        assertEquals("204", r.actual());
    }

    @Test
    void statusSupportsWildcardRangeAndCommaList() {
        List<AssertionResult> range = AssertionEvaluator.evaluateHttp(List.of(
                new AssertionSpec(AssertionSpec.HTTP_STATUS, null, AssertionSpec.OP_EQUALS, "200-299")),
                new HttpObservation(204, Map.of(), ""));
        assertTrue(range.get(0).pass());

        List<AssertionResult> list = AssertionEvaluator.evaluateHttp(List.of(
                new AssertionSpec(AssertionSpec.HTTP_STATUS, null, AssertionSpec.OP_EQUALS, "200,404")),
                new HttpObservation(404, Map.of(), ""));
        assertTrue(list.get(0).pass());
    }

    @Test
    void headerLookupIsCaseInsensitiveAndExplainsMiss() {
        HttpObservation obs = new HttpObservation(200,
                Map.of("Content-Type", List.of("application/json")), "{}");
        AssertionResult hit = one(AssertionEvaluator.evaluateHttp(List.of(new AssertionSpec(
                AssertionSpec.HEADER, "content-type", AssertionSpec.OP_CONTAINS, "json")), obs));
        assertTrue(hit.pass());
        assertEquals("application/json", hit.actual());

        AssertionResult miss = one(AssertionEvaluator.evaluateHttp(List.of(new AssertionSpec(
                AssertionSpec.HEADER, "X-Trace", AssertionSpec.OP_EXISTS, null)), obs));
        assertFalse(miss.pass());
        assertTrue(miss.message().contains("响应头缺失"));
    }

    @Test
    void jsonPathResolvesNestedArraysAndQuotedKeys() {
        String body = "{\"data\":{\"items\":[{\"name\":\"a\"}],\"flag\":true,\"empty\":null}}";
        HttpObservation obs = new HttpObservation(200, Map.of(), body);
        List<AssertionResult> results = AssertionEvaluator.evaluateHttp(List.of(
                new AssertionSpec(AssertionSpec.JSON_PATH, "$.data.items[0].name", AssertionSpec.OP_EQUALS, "a"),
                new AssertionSpec(AssertionSpec.JSON_PATH, "$.data['flag']", AssertionSpec.OP_EQUALS, "true"),
                new AssertionSpec(AssertionSpec.JSON_PATH, "$.data.empty", AssertionSpec.OP_NOT_EXISTS, null),
                new AssertionSpec(AssertionSpec.JSON_PATH, "$.missing", AssertionSpec.OP_EXISTS, null)), obs);
        assertTrue(results.get(0).pass(), results.get(0).message());
        assertTrue(results.get(1).pass(), results.get(1).message());
        assertTrue(results.get(2).pass(), results.get(2).message());
        assertFalse(results.get(3).pass());
    }

    @Test
    void invalidJsonFailsWithoutThrowing() {
        AssertionResult r = one(AssertionEvaluator.evaluateHttp(List.of(new AssertionSpec(
                AssertionSpec.JSON_PATH, "$.a", AssertionSpec.OP_EXISTS, null)),
                new HttpObservation(200, Map.of(), "not-json")));
        assertFalse(r.pass());
        assertTrue(r.message().contains("不是合法 JSON"));
    }

    @Test
    void bodyTextOpsCoverContainsNotContainsAndRegex() {
        HttpObservation obs = new HttpObservation(200, Map.of(), "trace-id: abc-123");
        List<AssertionResult> results = AssertionEvaluator.evaluateHttp(List.of(
                new AssertionSpec(AssertionSpec.BODY, null, AssertionSpec.OP_CONTAINS, "abc"),
                new AssertionSpec(AssertionSpec.BODY, null, AssertionSpec.OP_NOT_CONTAINS, "error"),
                new AssertionSpec(AssertionSpec.BODY, null, AssertionSpec.OP_REGEX, "abc-\\d+")), obs);
        assertTrue(results.stream().allMatch(AssertionResult::pass));
    }

    @Test
    void emptyCommandAssertionsDefaultToExitZero() {
        AssertionResult r = one(AssertionEvaluator.evaluateCommand(null,
                new CommandObservation(0, "", "", Map.of())));
        assertTrue(r.pass());
        assertEquals("0", r.expected());
    }

    @Test
    void exitCodeSupportsCommaListAndFailureMessage() {
        List<AssertionResult> results = AssertionEvaluator.evaluateCommand(List.of(
                new AssertionSpec(AssertionSpec.EXIT_CODE, null, AssertionSpec.OP_EQUALS, "0,2")),
                new CommandObservation(2, "", "", Map.of()));
        assertTrue(results.get(0).pass());

        AssertionResult bad = one(AssertionEvaluator.evaluateCommand(List.of(
                new AssertionSpec(AssertionSpec.EXIT_CODE, null, AssertionSpec.OP_EQUALS, "0")),
                new CommandObservation(3, "", "", Map.of())));
        assertFalse(bad.pass());
        assertEquals("3", bad.actual());
        assertTrue(bad.message().contains("期望退出码 0"));
    }

    @Test
    void stdoutAndStderrAssertionsWork() {
        CommandObservation obs = new CommandObservation(0, "BUILD SUCCESS", "warn: deprecated", Map.of());
        List<AssertionResult> results = AssertionEvaluator.evaluateCommand(List.of(
                new AssertionSpec(AssertionSpec.STDOUT, null, AssertionSpec.OP_CONTAINS, "SUCCESS"),
                new AssertionSpec(AssertionSpec.STDERR, null, AssertionSpec.OP_CONTAINS, "warn")), obs);
        assertTrue(results.stream().allMatch(AssertionResult::pass));
    }

    @Test
    void fileExistsAndSha256AssertionsUseObservationMap() {
        String sha = "a".repeat(64);
        Map<String, CommandObservation.FileState> files = Map.of(
                "target/report.xml", new CommandObservation.FileState(true, 12, sha));
        List<AssertionResult> results = AssertionEvaluator.evaluateCommand(List.of(
                new AssertionSpec(AssertionSpec.FILE_EXISTS, "target/report.xml", AssertionSpec.OP_EXISTS, null),
                new AssertionSpec(AssertionSpec.FILE_SHA256, "target/report.xml", AssertionSpec.OP_EQUALS, sha),
                new AssertionSpec(AssertionSpec.FILE_EXISTS, "target/missing.xml", AssertionSpec.OP_NOT_EXISTS, null)),
                new CommandObservation(0, "", "", files));
        assertTrue(results.get(0).pass());
        assertTrue(results.get(1).pass());
        assertTrue(results.get(2).pass());
    }

    @Test
    void shaMismatchAndAllPassAggregation() {
        Map<String, CommandObservation.FileState> files = Map.of(
                "a.bin", new CommandObservation.FileState(true, 1, "deadbeef"));
        List<AssertionResult> results = AssertionEvaluator.evaluateCommand(List.of(
                new AssertionSpec(AssertionSpec.FILE_SHA256, "a.bin", AssertionSpec.OP_EQUALS, "cafe")),
                new CommandObservation(0, "", "", files));
        assertFalse(results.get(0).pass());
        assertTrue(results.get(0).message().contains("sha256"));
        assertFalse(AssertionEvaluator.allPass(results));
        assertFalse(AssertionEvaluator.allPass(List.of()));
    }

    @Test
    void unsupportedSourceProducesFailureInsteadOfSilence() {
        AssertionResult r = one(AssertionEvaluator.evaluateHttp(List.of(new AssertionSpec(
                AssertionSpec.EXIT_CODE, null, AssertionSpec.OP_EQUALS, "0")),
                new HttpObservation(200, Map.of(), "{}")));
        assertFalse(r.pass());
        assertTrue(r.message().contains("不支持的 HTTP 断言"));
    }
}
