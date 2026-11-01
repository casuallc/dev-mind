package com.devmind.test.executor;

import com.devmind.common.program.ProgramContract;
import com.devmind.test.spec.AssertionResult;
import com.devmind.test.spec.AssertionSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * CAP-104 附录 C 程序输出解析：把 runner 分片上行的 result.json/artifacts 还原，
 * 并严格校验契约版本、字段类型、大小与 sha256。任何不合规都抛 IllegalArgumentException，
 * 由调用方映射 SCRIPT_ERROR，绝不把畸形输出当通过。
 */
public final class ProgramResultParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> STATUSES = List.of("pass", "fail", "blocked", "skip", "error");

    private ProgramResultParser() {
    }

    public static Collected collect(List<String> lines) {
        byte[] result = null;
        TreeMap<Integer, byte[]> resultChunks = new TreeMap<>();
        long resultSize = -1;
        String resultSha = null;
        Map<String, ArtifactMeta> meta = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, byte[]>> chunks = new LinkedHashMap<>();
        for (String line : lines == null ? List.<String>of() : lines) {
            if (line.startsWith(ProgramContract.RESULT_MARKER)) {
                String[] parts = line.substring(ProgramContract.RESULT_MARKER.length()).strip().split("\\s+");
                if (parts.length >= 2) {
                    result = new byte[0];
                    resultSize = size(parts[0]);
                    resultSha = parts[1];
                    resultChunks.clear();
                }
            } else if (line.startsWith(ProgramContract.RESULT_CHUNK_MARKER)) {
                String[] parts = line.substring(ProgramContract.RESULT_CHUNK_MARKER.length()).strip()
                        .split("\\s+", 2);
                if (parts.length == 2) {
                    try {
                        resultChunks.put(Integer.parseInt(parts[0]), Base64.getDecoder().decode(parts[1]));
                    } catch (IllegalArgumentException ignored) {
                        // 坏分片由最终 size/sha 校验拦下
                    }
                }
            } else if (line.startsWith(ProgramContract.ARTIFACT_MARKER)) {
                String[] parts = line.substring(ProgramContract.ARTIFACT_MARKER.length()).strip().split("\\s+");
                if (parts.length >= 3) {
                    try {
                        String name = decodeName(parts[0]);
                        meta.put(name, new ArtifactMeta(size(parts[1]), parts[2]));
                    } catch (IllegalArgumentException ignored) {
                        // 文件名/大小畸形，跳过该产物（后续仍可看到 stderr 提示）
                    }
                }
            } else if (line.startsWith(ProgramContract.ARTIFACT_CHUNK_MARKER)) {
                String[] parts = line.substring(ProgramContract.ARTIFACT_CHUNK_MARKER.length()).strip()
                        .split("\\s+", 3);
                if (parts.length == 3) {
                    try {
                        String name = decodeName(parts[0]);
                        chunks.computeIfAbsent(name, k -> new TreeMap<>())
                                .put(Integer.parseInt(parts[1]), Base64.getDecoder().decode(parts[2]));
                    } catch (IllegalArgumentException ignored) {
                        // 同上
                    }
                }
            }
        }
        byte[] resultBytes = result == null ? null : join(resultChunks);
        if (resultBytes != null) {
            if (resultSize >= 0 && resultBytes.length != resultSize) {
                throw new IllegalArgumentException("result.json 大小不符（声明 " + resultSize
                        + "，实际 " + resultBytes.length + "）");
            }
            if (resultSha != null && !resultSha.equalsIgnoreCase(sha256(resultBytes))) {
                throw new IllegalArgumentException("result.json sha256 不符");
            }
        }
        List<ProgramOutcome.ProgramArtifact> artifacts = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, ArtifactMeta> e : meta.entrySet()) {
            String path = e.getKey();
            validateArtifactPath(path);
            byte[] content = join(chunks.getOrDefault(path, new TreeMap<>()));
            if (content.length != e.getValue().size) {
                throw new IllegalArgumentException("artifact 大小不符: " + path + "（声明 "
                        + e.getValue().size + "，实际 " + content.length + "）");
            }
            String sha = sha256(content);
            if (!sha.equalsIgnoreCase(e.getValue().sha256)) {
                throw new IllegalArgumentException("artifact sha256 不符: " + path);
            }
            if (artifacts.size() >= ProgramContract.MAX_ARTIFACTS) {
                throw new IllegalArgumentException("artifact 数量超限（>" + ProgramContract.MAX_ARTIFACTS + "）");
            }
            if (content.length > ProgramContract.MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("artifact 单文件超限: " + path);
            }
            total += content.length;
            if (total > ProgramContract.MAX_ARTIFACTS_TOTAL_BYTES) {
                throw new IllegalArgumentException("artifact 总量超限");
            }
            artifacts.add(new ProgramOutcome.ProgramArtifact(path, content, sha));
        }
        return new Collected(resultBytes, artifacts);
    }

    /** 解析 result.json；契约不符抛 IllegalArgumentException。 */
    public static Decoded decode(byte[] json) {
        if (json == null || json.length == 0) {
            throw new IllegalArgumentException("程序未写出 result.json");
        }
        if (json.length > ProgramContract.MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("result.json 超限（" + json.length + " > "
                    + ProgramContract.MAX_RESULT_BYTES + " 字节）");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("result.json 不是合法 JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("result.json 必须是 JSON 对象");
        }
        String version = text(root, "contractVersion");
        if (!ProgramContract.CONTRACT_VERSION.equals(version)) {
            throw new IllegalArgumentException("result.contractVersion 必须为 \""
                    + ProgramContract.CONTRACT_VERSION + "\"（实际: " + version + "）");
        }
        String status = text(root, "status");
        status = status == null ? null : status.toLowerCase(Locale.ROOT);
        if (status == null || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("result.status 仅支持 pass/fail/blocked/skip/error（实际: " + status + "）");
        }
        String message = text(root, "message");
        if (message != null && message.length() > 4096) {
            message = message.substring(0, 4096) + "…[截断]";
        }
        List<AssertionResult> assertions = new ArrayList<>();
        JsonNode as = root.get("assertions");
        if (as != null && !as.isNull()) {
            if (!as.isArray()) {
                throw new IllegalArgumentException("result.assertions 必须是数组");
            }
            for (JsonNode a : as) {
                if (!a.isObject() || !a.has("pass") || !a.get("pass").isBoolean()) {
                    throw new IllegalArgumentException("result.assertions 每项必须含布尔 pass");
                }
                String expected = text(a, "expected");
                String actual = text(a, "actual");
                boolean pass = a.get("pass").asBoolean();
                String name = text(a, "name");
                assertions.add(new AssertionResult(AssertionSpec.CUSTOM,
                        name == null ? "assertion" : name,
                        text(a, "op") == null ? AssertionSpec.OP_EQUALS : text(a, "op"),
                        expected, actual, pass,
                        text(a, "message") == null ? (pass ? "通过" : "未通过") : text(a, "message")));
            }
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        JsonNode ms = root.get("metrics");
        if (ms != null && !ms.isNull()) {
            if (!ms.isObject()) {
                throw new IllegalArgumentException("result.metrics 必须是对象");
            }
            for (var e : ms.properties()) {
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) {
                    continue;
                }
                if (!v.isNumber()) {
                    throw new IllegalArgumentException("result.metrics 只允许数值: " + e.getKey());
                }
                // 三元运算符会把 Long/Double 提升为 double，这里分派后再装箱，保留整数语义
                Object num;
                if (v.asText("").matches("-?\\d+")) {
                    num = v.asLong();
                } else {
                    num = v.asDouble();
                }
                metrics.put(e.getKey(), num);
            }
        }
        return new Decoded(status, message, metrics, assertions);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    private static void validateArtifactPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("artifact 路径为空");
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("/") || p.startsWith("~") || p.contains("..") || p.contains(":") || p.startsWith("artifacts/")) {
            // runner 上报的是相对 $DEVMIND_ARTIFACTS 的路径；带 artifacts/ 前缀说明契约被误用
            throw new IllegalArgumentException("artifact 路径必须是 $DEVMIND_ARTIFACTS 下的相对路径: " + path);
        }
    }

    private static String decodeName(String b64) {
        String name = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        if (name.isBlank()) {
            throw new IllegalArgumentException("artifact 名称为空");
        }
        return name;
    }

    private static long size(String s) {
        try {
            long n = Long.parseLong(s);
            if (n < 0) {
                throw new NumberFormatException("negative");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("artifact 大小非法: " + s);
        }
    }

    private static byte[] join(TreeMap<Integer, byte[]> chunks) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int expected = 0;
        for (Map.Entry<Integer, byte[]> e : chunks.entrySet()) {
            if (e.getKey() != expected) {
                throw new IllegalArgumentException("分片序号不连续：期望 " + expected + "，实际 " + e.getKey());
            }
            out.writeBytes(e.getValue());
            expected++;
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public record Collected(byte[] resultJson, List<ProgramOutcome.ProgramArtifact> artifacts) {
    }

    public record Decoded(String status, String message, Map<String, Object> metrics,
                          List<AssertionResult> assertions) {
    }

    private record ArtifactMeta(long size, String sha256) {
    }
}
