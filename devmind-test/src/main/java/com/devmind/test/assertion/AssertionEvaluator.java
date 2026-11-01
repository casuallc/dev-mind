package com.devmind.test.assertion;

import com.devmind.test.spec.AssertionResult;
import com.devmind.test.spec.AssertionSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * CAP-104 断言执行器：HTTP（status/header/JSONPath/body）与 COMMAND（exitCode/stdout/stderr/文件）。
 * 每条断言都产出 expected/actual/pass/message，失败时能解释差异，不只给“失败”。
 */
public final class AssertionEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssertionEvaluator() {
    }

    public static List<AssertionResult> evaluateHttp(List<AssertionSpec> assertions, HttpObservation obs) {
        List<AssertionSpec> list = assertions == null || assertions.isEmpty()
                ? List.of(new AssertionSpec(AssertionSpec.HTTP_STATUS, null, AssertionSpec.OP_EQUALS, "2XX"))
                : assertions;
        List<AssertionResult> out = new ArrayList<>();
        for (AssertionSpec a : list) {
            if (a == null) {
                continue;
            }
            String source = upper(a.source());
            switch (source) {
                case AssertionSpec.HTTP_STATUS -> out.add(checkStatus(a, obs.status()));
                case AssertionSpec.HEADER -> out.add(checkHeader(a, obs.headers()));
                case AssertionSpec.JSON_PATH -> out.add(checkJsonPath(a, obs.body()));
                case AssertionSpec.BODY -> out.add(checkText(a, source, obs.body() == null ? "" : obs.body()));
                default -> out.add(fail(a, source, "不支持的 HTTP 断言 source"));
            }
        }
        return out;
    }

    public static List<AssertionResult> evaluateCommand(List<AssertionSpec> assertions, CommandObservation obs) {
        List<AssertionSpec> list = assertions == null || assertions.isEmpty()
                ? List.of(new AssertionSpec(AssertionSpec.EXIT_CODE, null, AssertionSpec.OP_EQUALS, "0"))
                : assertions;
        List<AssertionResult> out = new ArrayList<>();
        for (AssertionSpec a : list) {
            if (a == null) {
                continue;
            }
            String source = upper(a.source());
            switch (source) {
                case AssertionSpec.EXIT_CODE -> out.add(checkExitCode(a, obs.exitCode()));
                case AssertionSpec.STDOUT -> out.add(checkText(a, source, obs.stdout() == null ? "" : obs.stdout()));
                case AssertionSpec.STDERR -> out.add(checkText(a, source, obs.stderr() == null ? "" : obs.stderr()));
                case AssertionSpec.FILE_EXISTS -> out.add(checkFileExists(a, obs.files()));
                case AssertionSpec.FILE_SHA256 -> out.add(checkFileSha(a, obs.files()));
                default -> out.add(fail(a, source, "不支持的 COMMAND 断言 source"));
            }
        }
        return out;
    }

    public static boolean allPass(List<AssertionResult> results) {
        return results != null && !results.isEmpty() && results.stream().allMatch(AssertionResult::pass);
    }

    // ---------------- 各类断言 ----------------

    private static AssertionResult checkStatus(AssertionSpec a, int actual) {
        String expected = a.expected() == null ? "2XX" : a.expected().strip();
        boolean pass = statusMatches(expected, actual);
        String message = pass ? "状态码符合" : "期望状态 " + expected + "，实际 " + actual;
        return new AssertionResult(AssertionSpec.HTTP_STATUS, null, normOp(a.op()),
                expected, String.valueOf(actual), pass, message);
    }

    private static boolean statusMatches(String expected, int actual) {
        for (String part : expected.split(",")) {
            String s = part.strip().toUpperCase(Locale.ROOT);
            if (s.endsWith("XX") && s.length() == 3) {
                if (actual / 100 == s.charAt(0) - '0') {
                    return true;
                }
            } else if (s.contains("-")) {
                String[] range = s.split("-", 2);
                try {
                    int lo = Integer.parseInt(range[0].strip());
                    int hi = Integer.parseInt(range[1].strip());
                    if (actual >= lo && actual <= hi) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // 非法范围按不匹配处理
                }
            } else {
                try {
                    if (Integer.parseInt(s) == actual) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // 非法状态按不匹配处理
                }
            }
        }
        return false;
    }

    private static AssertionResult checkHeader(AssertionSpec a, Map<String, List<String>> headers) {
        String name = a.selector() == null ? "" : a.selector().strip().toLowerCase(Locale.ROOT);
        String actual = null;
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    actual = e.getValue() == null || e.getValue().isEmpty() ? "" : e.getValue().get(0);
                    break;
                }
            }
        }
        String op = normOp(a.op());
        boolean pass;
        String message;
        if (AssertionSpec.OP_EXISTS.equals(op)) {
            pass = actual != null;
            message = pass ? "响应头存在" : "响应头缺失: " + name;
        } else if (AssertionSpec.OP_NOT_EXISTS.equals(op)) {
            pass = actual == null;
            message = pass ? "响应头不存在" : "响应头不应存在: " + name;
        } else {
            pass = compareText(op, a.expected(), actual == null ? "" : actual);
            message = pass ? "响应头符合" : "响应头 " + name + " 期望 " + a.expected() + "，实际 " + actual;
        }
        return new AssertionResult(AssertionSpec.HEADER, name, op, a.expected(), actual, pass, message);
    }

    private static AssertionResult checkJsonPath(AssertionSpec a, String body) {
        String path = a.selector() == null ? "" : a.selector().strip();
        String op = normOp(a.op());
        JsonNode node;
        String actual = null;
        boolean found = false;
        try {
            JsonNode root = body == null || body.isBlank() ? null : MAPPER.readTree(body);
            node = root == null ? null : at(root, path);
            found = node != null && !node.isMissingNode() && !node.isNull();
            actual = found ? (node.isTextual() || node.isNumber() || node.isBoolean() ? node.asText() : node.toString()) : null;
        } catch (Exception e) {
            return failWith(op, AssertionSpec.JSON_PATH, path, a.expected(), null, "响应不是合法 JSON: " + e.getMessage());
        }
        boolean pass;
        String message;
        if (AssertionSpec.OP_EXISTS.equals(op)) {
            pass = found;
            message = pass ? "JSONPath 存在" : "JSONPath 不存在: " + path;
        } else if (AssertionSpec.OP_NOT_EXISTS.equals(op)) {
            pass = !found;
            message = pass ? "JSONPath 不存在" : "JSONPath 不应存在: " + path;
        } else {
            pass = found && compareText(op, a.expected(), actual);
            message = pass ? "JSONPath 值符合" : "JSONPath " + path + " 期望 " + a.expected() + "，实际 " + actual;
        }
        return new AssertionResult(AssertionSpec.JSON_PATH, path, op, a.expected(), actual, pass, message);
    }

    private static AssertionResult checkText(AssertionSpec a, String source, String actual) {
        String op = normOp(a.op());
        boolean pass;
        String message;
        if (AssertionSpec.OP_NOT_CONTAINS.equals(op)) {
            pass = actual == null || !actual.contains(a.expected() == null ? "" : a.expected());
            message = pass ? "不包含期望片段" : "不应包含: " + a.expected();
        } else {
            pass = compareText(op, a.expected(), actual);
            message = pass ? "内容符合" : source + " 期望 " + describeOp(op) + " " + a.expected();
        }
        return new AssertionResult(source, a.selector(), op, a.expected(), actual, pass, message);
    }

    private static AssertionResult checkExitCode(AssertionSpec a, Integer exitCode) {
        String actual = exitCode == null ? null : String.valueOf(exitCode);
        String expected = a.expected() == null ? "0" : a.expected().strip();
        boolean pass = false;
        for (String part : expected.split(",")) {
            if (part.strip().equals(actual)) {
                pass = true;
                break;
            }
        }
        String message = pass ? "退出码符合" : "期望退出码 " + expected + "，实际 " + actual;
        return new AssertionResult(AssertionSpec.EXIT_CODE, null, normOp(a.op()), expected, actual, pass, message);
    }

    private static AssertionResult checkFileExists(AssertionSpec a, Map<String, CommandObservation.FileState> files) {
        String path = a.selector() == null ? "" : a.selector().strip();
        CommandObservation.FileState state = files == null ? null : files.get(path);
        boolean exists = state != null && state.exists();
        String op = normOp(a.op());
        boolean expectExists = AssertionSpec.OP_EXISTS.equals(op)
                || (AssertionSpec.OP_EQUALS.equals(op) && a.expected() != null
                && ("true".equalsIgnoreCase(a.expected().strip()) || "1".equals(a.expected().strip())));
        boolean pass = expectExists == exists;
        String message = pass ? "文件存在性符合" : "文件 " + path + (expectExists ? " 不存在" : " 不应存在");
        return new AssertionResult(AssertionSpec.FILE_EXISTS, path, op,
                String.valueOf(expectExists), String.valueOf(exists), pass, message);
    }

    private static AssertionResult checkFileSha(AssertionSpec a, Map<String, CommandObservation.FileState> files) {
        String path = a.selector() == null ? "" : a.selector().strip();
        CommandObservation.FileState state = files == null ? null : files.get(path);
        String actual = state == null ? null : state.sha256();
        boolean pass = actual != null && actual.equalsIgnoreCase(a.expected() == null ? "" : a.expected().strip());
        String message = pass ? "文件哈希符合" : "文件 " + path + " 期望 sha256 " + a.expected() + "，实际 " + actual;
        return new AssertionResult(AssertionSpec.FILE_SHA256, path, normOp(a.op()),
                a.expected(), actual, pass, message);
    }

    // ---------------- 工具 ----------------

    private static boolean compareText(String op, String expected, String actual) {
        String exp = expected == null ? "" : expected;
        String act = actual == null ? "" : actual;
        return switch (op) {
            case AssertionSpec.OP_EQUALS -> act.equals(exp);
            case AssertionSpec.OP_CONTAINS -> act.contains(exp);
            case AssertionSpec.OP_NOT_CONTAINS -> !act.contains(exp);
            case AssertionSpec.OP_REGEX -> regex(exp, act);
            case AssertionSpec.OP_EXISTS -> !act.isEmpty();
            case AssertionSpec.OP_NOT_EXISTS -> act.isEmpty();
            default -> false;
        };
    }

    private static boolean regex(String pattern, String actual) {
        try {
            return Pattern.compile(pattern).matcher(actual).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private static String describeOp(String op) {
        return switch (op) {
            case AssertionSpec.OP_CONTAINS -> "包含";
            case AssertionSpec.OP_NOT_CONTAINS -> "不包含";
            case AssertionSpec.OP_REGEX -> "匹配正则";
            case AssertionSpec.OP_EQUALS -> "等于";
            default -> op;
        };
    }

    private static AssertionResult fail(AssertionSpec a, String source, String message) {
        return new AssertionResult(source, a.selector(), normOp(a.op()), a.expected(), null, false, message);
    }

    private static AssertionResult failWith(String op, String source, String selector,
                                            String expected, String actual, String message) {
        return new AssertionResult(source, selector, op, expected, actual, false, message);
    }

    private static String normOp(String op) {
        return op == null || op.isBlank() ? AssertionSpec.OP_EQUALS : op.strip().toUpperCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
    }

    /** 简化 JSONPath：$.a.b[0]、a.b、$.a['b']（POC 不引入额外依赖）。 */
    static JsonNode at(JsonNode root, String path) {
        String p = path == null ? "" : path.strip();
        if (p.startsWith("$")) {
            p = p.substring(1);
        }
        if (p.startsWith(".")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return root;
        }
        JsonNode cur = root;
        int i = 0;
        StringBuilder token = new StringBuilder();
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') {
                cur = child(cur, token.toString());
                token.setLength(0);
                i++;
            } else if (c == '[') {
                int close = p.indexOf(']', i);
                if (close < 0) {
                    return null;
                }
                String inside = p.substring(i + 1, close).strip();
                if (token.length() > 0) {
                    cur = child(cur, token.toString());
                    token.setLength(0);
                }
                if (cur == null) {
                    return null;
                }
                if (inside.startsWith("'") || inside.startsWith("\"")) {
                    cur = child(cur, inside.substring(1, inside.length() - 1));
                } else {
                    try {
                        int idx = Integer.parseInt(inside);
                        cur = cur.isArray() && idx >= 0 && idx < cur.size() ? cur.get(idx) : null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                i = close + 1;
            } else {
                token.append(c);
                i++;
            }
        }
        if (token.length() > 0) {
            cur = child(cur, token.toString());
        }
        return cur;
    }

    private static JsonNode child(JsonNode node, String name) {
        if (node == null || name.isEmpty()) {
            return node;
        }
        if (!node.isObject()) {
            return null;
        }
        JsonNode v = node.get(name);
        return v == null ? null : v;
    }
}
