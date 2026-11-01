package com.devmind.common.program;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-104 附录 C.1 程序包清单 {@code .devmind-program.json}。
 *
 * <p>只支持 {@code contractVersion="1"}；运行时/目标 OS/架构仅作执行前匹配，
 * 入口脚本必须是包内相对路径且不以 {@code ..}、绝对路径或盘符逃逸。
 * 平台不构建：清单声明的是已经构建好的程序。</p>
 */
public record ProgramManifest(
        String contractVersion,
        String name,
        String version,
        String runtime,
        String runtimeVersion,
        String entrypoint,
        String targetOs,
        String targetArch,
        String description) {

    public static final Set<String> RUNTIMES = Set.of("JAVA", "NATIVE", "NODE", "PYTHON", "SHELL");
    public static final Set<String> OSES = Set.of("ANY", "LINUX", "WINDOWS", "MACOS");
    public static final Set<String> ARCHES = Set.of("ANY", "X86_64", "ARM64");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?.*");
    private static final Pattern BUILD_COMMAND = Pattern.compile(
            "(?i)(?:^|[\\s;&|(])(mvn|mvnw|gradle|gradlew|npm\\s+install|npm\\s+ci|yarn|pnpm\\s+install"
                    + "|pip3?\\s+install|poetry\\s+install|go\\s+build|cargo\\s+build)\\b");

    public ProgramManifest {
        runtime = normalizeEnum(runtime, RUNTIMES, "运行时");
        targetOs = normalizeEnum(targetOs, OSES, "目标 OS");
        targetArch = normalizeEnum(targetArch, ARCHES, "目标架构");
    }

    /** 从 JSON 文本解析并做基础字段校验；失败抛 IllegalArgumentException（调用方转 400）。 */
    public static ProgramManifest parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("程序清单为空");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("程序清单不是合法 JSON: " + e.getMessage());
        }
        ProgramManifest m = new ProgramManifest(
                text(node, "contractVersion"),
                text(node, "name"),
                text(node, "version"),
                text(node, "runtime"),
                text(node, "runtimeVersion"),
                text(node, "entrypoint"),
                text(node, "targetOs"),
                text(node, "targetArch"),
                text(node, "description"));
        m.validate();
        return m;
    }

    public void validate() {
        if (!ProgramContract.CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("程序清单 contractVersion 必须为 \""
                    + ProgramContract.CONTRACT_VERSION + "\"（实际: " + contractVersion + "）");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("程序清单 name 必填");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("程序清单 version 必填");
        }
        if (runtime == null) {
            throw new IllegalArgumentException("程序清单 runtime 必填，可选 " + String.join("/", RUNTIMES));
        }
        validateEntrypoint(entrypoint);
        if (targetOs == null) {
            throw new IllegalArgumentException("程序清单 targetOs 可选 ANY/LINUX/WINDOWS/MACOS");
        }
        if (targetArch == null) {
            throw new IllegalArgumentException("程序清单 targetArch 可选 ANY/X86_64/ARM64");
        }
    }

    /** 入口必须是包内相对路径、普通脚本文件；拒绝绝对路径/.. /盘符。 */
    public static void validateEntrypoint(String entrypoint) {
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new IllegalArgumentException("程序清单 entrypoint 必填");
        }
        String p = entrypoint.strip().replace('\\', '/');
        if (p.startsWith("/") || p.contains("..") || p.contains(":") || p.startsWith("~")) {
            throw new IllegalArgumentException("程序入口必须是包内相对路径: " + entrypoint);
        }
        String lower = p.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".sh") || lower.endsWith(".bash"))) {
            throw new IllegalArgumentException("程序入口必须是 shell 脚本（.sh/.bash）: " + entrypoint);
        }
    }

    /** 禁止执行时构建/装依赖：入口脚本出现构建命令即拒绝上传（显示具体命令）。 */
    public static void rejectBuildCommands(String entrypoint, String scriptText) {
        if (scriptText == null || scriptText.isEmpty()) {
            return;
        }
        Matcher m = BUILD_COMMAND.matcher(scriptText);
        if (m.find()) {
            throw new IllegalArgumentException(
                    "程序入口 " + entrypoint + " 含构建/依赖安装命令（" + m.group(1)
                            + "）；平台只执行已构建好的产物，请把程序与依赖打进 ZIP");
        }
    }

    /** 节点 OS 是否匹配；ANY 恒匹配。 */
    public boolean matchesOs(String nodeOs) {
        if ("ANY".equals(targetOs) || nodeOs == null || nodeOs.isBlank()) {
            return true;
        }
        String os = nodeOs.toLowerCase(Locale.ROOT);
        return switch (targetOs) {
            case "WINDOWS" -> os.contains("win");
            case "LINUX" -> os.contains("linux");
            case "MACOS" -> os.contains("mac") || os.contains("darwin");
            default -> true;
        };
    }

    /** 节点架构是否匹配；ANY 恒匹配；节点未上报（null）时不误伤。 */
    public boolean matchesArch(String nodeArch) {
        if ("ANY".equals(targetArch) || nodeArch == null || nodeArch.isBlank()) {
            return true;
        }
        String arch = nodeArch.toLowerCase(Locale.ROOT);
        return switch (targetArch) {
            case "X86_64" -> arch.contains("x86_64") || arch.contains("amd64") || arch.contains("x64");
            case "ARM64" -> arch.contains("aarch64") || arch.contains("arm64");
            default -> true;
        };
    }

    /** 运行时版本约束：支持空（不约束）、精确前缀、{@code >=x.y.z}、{@code >x.y.z}。 */
    public boolean satisfiesRuntimeVersion(String actual) {
        if (runtimeVersion == null || runtimeVersion.isBlank()) {
            return true;
        }
        if (actual == null || actual.isBlank()) {
            return false;
        }
        String constraint = runtimeVersion.strip();
        boolean gte = constraint.startsWith(">=");
        boolean gt = !gte && constraint.startsWith(">");
        String wanted = constraint.replaceFirst("^>=?", "").strip();
        int[] w = parts(wanted);
        int[] a = parts(actual);
        if (w == null || a == null) {
            return actual.startsWith(wanted);
        }
        int cmp = compare(a, w);
        return gte ? cmp >= 0 : gt ? cmp > 0 : cmp == 0;
    }

    /** 程序包声明的必要工具链键：JAVA→java、NODE→node、PYTHON→python、SHELL/NATIVE 无。 */
    public String requiredToolchainKey() {
        return switch (runtime) {
            case "JAVA" -> "java";
            case "NODE" -> "node";
            case "PYTHON" -> "python";
            default -> null;
        };
    }

    /** 从节点 hello 的 os 字段（如 "Linux / amd64"）提取架构片段。 */
    public static String archOfNodeOs(String nodeOs) {
        if (nodeOs == null) {
            return null;
        }
        int slash = nodeOs.lastIndexOf('/');
        return slash < 0 ? null : nodeOs.substring(slash + 1).strip();
    }

    public boolean declaresArtifact(String relative) {
        return relative != null && !relative.isBlank();
    }

    public List<String> summary() {
        return List.of(name == null ? "" : name, version == null ? "" : version,
                runtime == null ? "" : runtime, entrypoint == null ? "" : entrypoint);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    private static String normalizeEnum(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) {
            if ("targetOs".equals(label) || "目标 OS".equals(label)) {
                return "ANY";
            }
            if ("targetArch".equals(label) || "目标架构".equals(label)) {
                return "ANY";
            }
            return null;
        }
        String v = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("MAC".equals(v) || "OSX".equals(v) || "DARWIN".equals(v)) {
            v = "MACOS";
        }
        if ("AMD64".equals(v) || "X64".equals(v)) {
            v = "X86_64";
        }
        if ("AARCH64".equals(v)) {
            v = "ARM64";
        }
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(label + " 不合法: " + value + "（可选 " + String.join("/", allowed) + "）");
        }
        return v;
    }

    private static int[] parts(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = VERSION.matcher(version);
        if (!m.matches()) {
            return null;
        }
        return new int[]{
                Integer.parseInt(m.group(1)),
                m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    /** 读取包内清单的便捷方法（runner 使用，失败转 IOException 由拉包侧收口）。 */
    public static ProgramManifest readFromPackage(java.util.function.Function<String, byte[]> reader) throws IOException {
        byte[] bytes = reader.apply(ProgramContract.MANIFEST_NAME);
        if (bytes == null) {
            throw new IOException("程序包缺少 " + ProgramContract.MANIFEST_NAME);
        }
        try {
            return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException("程序清单校验失败: " + e.getMessage(), e);
        }
    }
}
