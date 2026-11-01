package com.devmind.test.spec;

import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-104 CaseSpec 校验回归：类型/位置/断言组合/路径安全。 */
class CaseSpecValidatorTest {

    private static CaseSpec.HttpSpec http() {
        return new CaseSpec.HttpSpec("GET", "/api/health", null, null, null);
    }

    private static CaseSpec spec(String type, CaseSpec.HttpSpec http, CaseSpec.CommandSpec cmd,
                                 List<AssertionSpec> assertions) {
        return new CaseSpec("case-1", "健康检查", type, "P1", List.of(), null, null,
                assertions, null, new CaseSpec.ExecutorSpec(null, null, null, 30, null, null),
                http, cmd, null, null);
    }

    @Test
    void validHttpSpecPasses() {
        List<String> errors = CaseSpecValidator.validate(spec(CaseSpec.HTTP, http(),
                null, List.of(new AssertionSpec(AssertionSpec.HTTP_STATUS, null, AssertionSpec.OP_EQUALS, "2XX"))));
        assertTrue(errors.isEmpty(), String.join("; ", errors));
    }

    @Test
    void httpWithoutPathFails() {
        List<String> errors = CaseSpecValidator.validate(spec(CaseSpec.HTTP,
                new CaseSpec.HttpSpec("GET", " ", null, null, null), null, null));
        assertTrue(errors.stream().anyMatch(e -> e.contains("HTTP path 必填")));
    }

    @Test
    void commandOnServerRejected() {
        CaseSpec s = new CaseSpec("cmd-1", "脚本", CaseSpec.COMMAND, "P1", null, null, null, null, null,
                new CaseSpec.ExecutorSpec(CaseSpec.LOCATION_SERVER, null, null, null, null, null),
                null, new CaseSpec.CommandSpec("mvn -q test", null, null), null, null);
        List<String> errors = CaseSpecValidator.validate(s);
        assertTrue(errors.stream().anyMatch(e -> e.contains("必须在节点执行")));
    }

    @Test
    void commandWorkingDirMustStayRelative() {
        CaseSpec s = new CaseSpec("cmd-2", "脚本", CaseSpec.COMMAND, "P1", null, null, null, null, null,
                null, null, new CaseSpec.CommandSpec("mvn -q test", "../escape", null), null, null);
        List<String> errors = CaseSpecValidator.validate(s);
        assertTrue(errors.stream().anyMatch(e -> e.contains("workingDir")));
    }

    @Test
    void manualWithAssertionsRejected() {
        CaseSpec s = new CaseSpec("manual-1", "人工验收", CaseSpec.MANUAL, "P1", null, null, null,
                List.of(new AssertionSpec(AssertionSpec.BODY, null, AssertionSpec.OP_CONTAINS, "x")),
                null, null, null, null, new CaseSpec.ManualSpec("按步骤验收"), null);
        List<String> errors = CaseSpecValidator.validate(s);
        assertTrue(errors.stream().anyMatch(e -> e.contains("MANUAL 用例不支持自动断言")));
    }

    @Test
    void assertionSourceAndSelectorAreChecked() {
        CaseSpec s = spec(CaseSpec.HTTP, http(), null, List.of(
                new AssertionSpec(AssertionSpec.EXIT_CODE, null, AssertionSpec.OP_EQUALS, "0"),
                new AssertionSpec(AssertionSpec.HEADER, " ", AssertionSpec.OP_EXISTS, null)));
        List<String> errors = CaseSpecValidator.validate(s);
        assertTrue(errors.stream().anyMatch(e -> e.contains("不支持断言 source")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("HEADER 断言缺少 selector")));
    }

    @Test
    void assertionNeedsExpectedUnlessExistOps() {
        CaseSpec s = spec(CaseSpec.HTTP, http(), null, List.of(
                new AssertionSpec(AssertionSpec.JSON_PATH, "$.code", AssertionSpec.OP_EQUALS, null),
                new AssertionSpec(AssertionSpec.JSON_PATH, "$.ok", AssertionSpec.OP_EXISTS, null)));
        List<String> errors = CaseSpecValidator.validate(s);
        assertEquals(1, errors.size(), String.join("; ", errors));
        assertTrue(errors.get(0).contains("缺少 expected"));
    }

    @Test
    void badCaseKeyAndTimeoutRejected() {
        CaseSpec s = new CaseSpec("中文 key", "t", CaseSpec.HTTP, null, null, null, null, null, null,
                new CaseSpec.ExecutorSpec(null, null, null, 0, null, null), http(), null, null, null);
        List<String> errors = CaseSpecValidator.validate(s);
        assertTrue(errors.stream().anyMatch(e -> e.contains("caseKey")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("timeoutSec")));
    }

    @Test
    void validateOrThrowCarriesAllMessages() {
        DevMindException ex = assertThrows(DevMindException.class,
                () -> CaseSpecValidator.validateOrThrow(spec("UNKNOWN", null, null, null)));
        assertTrue(ex.getMessage().contains("HTTP / COMMAND / MANUAL"));
    }

    @Test
    void nullSpecRejected() {
        assertEquals(List.of("CaseSpec 不能为空"), CaseSpecValidator.validate(null));
    }

    @Test
    void mapBasedSpecIsTolerantOfNulls() {
        CaseSpec s = new CaseSpec(null, "t", "http", null, null, null, null, null, null,
                new CaseSpec.ExecutorSpec(null, null, null, null, null, Map.of()), http(), null, null, null);
        assertTrue(CaseSpecValidator.validate(s).isEmpty());
    }
}
