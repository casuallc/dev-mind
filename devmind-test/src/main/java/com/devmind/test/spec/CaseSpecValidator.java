package com.devmind.test.spec;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CAP-104 CaseSpec 校验：类型/必填/执行位置/断言组合/路径安全。返回全部错误，便于前端一次改完。
 */
public final class CaseSpecValidator {

    private static final Pattern CASE_KEY = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern METHOD = Pattern.compile("^[A-Za-z]{1,16}$");
    private static final Set<String> TYPES = Set.of(CaseSpec.HTTP, CaseSpec.COMMAND, CaseSpec.MANUAL);
    private static final Set<String> HTTP_ASSERTIONS = Set.of(
            AssertionSpec.HTTP_STATUS, AssertionSpec.HEADER, AssertionSpec.JSON_PATH, AssertionSpec.BODY);
    private static final Set<String> COMMAND_ASSERTIONS = Set.of(
            AssertionSpec.EXIT_CODE, AssertionSpec.STDOUT, AssertionSpec.STDERR,
            AssertionSpec.FILE_EXISTS, AssertionSpec.FILE_SHA256, AssertionSpec.CUSTOM);
    private static final Set<String> OPS = Set.of(
            AssertionSpec.OP_EQUALS, AssertionSpec.OP_CONTAINS, AssertionSpec.OP_NOT_CONTAINS,
            AssertionSpec.OP_REGEX, AssertionSpec.OP_EXISTS, AssertionSpec.OP_NOT_EXISTS);

    private CaseSpecValidator() {
    }

    public static List<String> validate(CaseSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            errors.add("CaseSpec 不能为空");
            return errors;
        }
        if (spec.title() == null || spec.title().isBlank()) {
            errors.add("title 必填");
        }
        String type = upper(spec.type());
        if (type == null || !TYPES.contains(type)) {
            errors.add("type 必须是 HTTP / COMMAND / MANUAL");
        }
        if (spec.caseKey() != null && !spec.caseKey().isBlank() && !CASE_KEY.matcher(spec.caseKey()).matches()) {
            errors.add("caseKey 只能含字母数字 ._-（≤128 字符）");
        }
        if (spec.executor() != null && spec.executor().timeoutSec() != null
                && (spec.executor().timeoutSec() <= 0 || spec.executor().timeoutSec() > 86_400)) {
            errors.add("timeoutSec 必须在 1~86400 之间");
        }
        if (CaseSpec.HTTP.equals(type)) {
            validateHttp(spec, errors);
        } else if (CaseSpec.COMMAND.equals(type)) {
            validateCommand(spec, errors);
        } else if (CaseSpec.MANUAL.equals(type)) {
            if (spec.manual() != null && spec.manual().instructions() != null
                    && spec.manual().instructions().length() > 4000) {
                errors.add("manual.instructions 超长（≤4000）");
            }
        }
        validateAssertions(spec, type, errors);
        return errors;
    }

    public static void validateOrThrow(CaseSpec spec) {
        List<String> errors = validate(spec);
        if (!errors.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "用例规范校验失败: " + String.join("; ", errors));
        }
    }

    private static void validateHttp(CaseSpec spec, List<String> errors) {
        CaseSpec.HttpSpec http = spec.http();
        if (http == null) {
            errors.add("HTTP 用例缺少 http 定义");
            return;
        }
        if (http.method() == null || !METHOD.matcher(http.method()).matches()) {
            errors.add("HTTP method 非法: " + http.method());
        }
        if (http.path() == null || http.path().isBlank()) {
            errors.add("HTTP path 必填");
        }
        String loc = location(spec, CaseSpec.LOCATION_SERVER);
        if (!CaseSpec.LOCATION_SERVER.equals(loc) && !CaseSpec.LOCATION_NODE.equals(loc)) {
            errors.add("HTTP executor.location 必须是 SERVER / NODE");
        }
    }

    private static void validateCommand(CaseSpec spec, List<String> errors) {
        CaseSpec.CommandSpec cmd = spec.command();
        if (cmd == null || cmd.command() == null || cmd.command().isBlank()) {
            errors.add("COMMAND 用例缺少 command");
        }
        String loc = location(spec, CaseSpec.LOCATION_NODE);
        if (!CaseSpec.LOCATION_NODE.equals(loc)) {
            errors.add("COMMAND 用例必须在节点执行（executor.location=NODE）");
        }
        if (cmd != null && cmd.workingDir() != null && !cmd.workingDir().isBlank()) {
            String wd = cmd.workingDir().strip().replace('\\', '/');
            if (wd.startsWith("/") || wd.contains("..")) {
                errors.add("command.workingDir 必须是相对路径且不能含 ..");
            }
        }
        if (cmd != null && cmd.contractVersion() != null && !cmd.contractVersion().isBlank()) {
            String contract = cmd.contractVersion().strip();
            if (!"1".equals(contract)) {
                errors.add("command.contractVersion 只支持 \"1\"（空 = 普通 COMMAND 语义）");
            } else if (spec.source() == null || spec.source().assetId() == null) {
                errors.add("程序契约 v1 要求 source.kind=ASSET 且提供 source.assetId（已评审程序包）");
            }
        }
    }

    private static void validateAssertions(CaseSpec spec, String type, List<String> errors) {
        List<AssertionSpec> assertions = spec.assertions();
        if (assertions == null || assertions.isEmpty()) {
            return;
        }
        for (AssertionSpec a : assertions) {
            if (a == null) {
                errors.add("assertion 条目不能为空");
                continue;
            }
            String source = upper(a.source());
            String op = upper(a.op());
            if (source == null) {
                errors.add("assertion.source 必填");
                continue;
            }
            if (op == null || !OPS.contains(op)) {
                errors.add("assertion.op 非法: " + a.op());
            }
            if (CaseSpec.HTTP.equals(type) && !HTTP_ASSERTIONS.contains(source)) {
                errors.add("HTTP 用例不支持断言 source: " + source);
            }
            if (CaseSpec.COMMAND.equals(type) && !COMMAND_ASSERTIONS.contains(source)) {
                errors.add("COMMAND 用例不支持断言 source: " + source);
            }
            if (CaseSpec.MANUAL.equals(type)) {
                errors.add("MANUAL 用例不支持自动断言");
            }
            if ((AssertionSpec.HEADER.equals(source) || AssertionSpec.JSON_PATH.equals(source)
                    || AssertionSpec.FILE_EXISTS.equals(source) || AssertionSpec.FILE_SHA256.equals(source))
                    && (a.selector() == null || a.selector().isBlank())) {
                errors.add(source + " 断言缺少 selector");
            }
            if (a.expected() == null && !AssertionSpec.OP_EXISTS.equals(op)
                    && !AssertionSpec.OP_NOT_EXISTS.equals(op)) {
                errors.add(source + "/" + op + " 断言缺少 expected");
            }
        }
    }

    private static String location(CaseSpec spec, String fallback) {
        if (spec.executor() == null || spec.executor().location() == null || spec.executor().location().isBlank()) {
            return fallback;
        }
        return spec.executor().location().strip().toUpperCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }
}
