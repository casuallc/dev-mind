package com.devmind.agent.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * CAP-66：扫描引擎工具路径解析。exec 帧 env 带 {@code DEVMIND_SCAN_ENGINE} 时，
 * 按 {@code agent.properties} 的 {@code scan.<engine>.path}（指向二进制或其所在目录）解析出
 * 待 prepend 的目录注入 ProcessBuilder PATH；未配置则按平台 where/which 探测，
 * 探测不到即 exec 失败（服务端该引擎记 FAILED，提示文案引导补配置）。
 *
 * <p>独立加载（不进 {@link RunnerConfig} record）——record 已 ~30 个组件带一串兼容构造器，
 * 扫描路径是边缘配置，单独从 properties 文件解析。</p>
 */
public final class ScanToolResolver {

    public static final String ENV_ENGINE = "DEVMIND_SCAN_ENGINE";

    /** 引擎 → 裸命令名（探测用；与服务端 ScanEngineAdapter.binaryName 同源） */
    private static final Map<String, String> BINARIES = Map.of(
            "sca", "dependency-check",
            "sast", "semgrep",
            "secrets", "gitleaks");

    private ScanToolResolver() {
    }

    /** 解析 agent.properties 的 scan.&lt;engine&gt;.path；未知引擎 fail-fast（防手滑配错静默不生效）。 */
    public static Map<String, String> load(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        Properties p = new Properties();
        try (InputStreamReader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new IllegalStateException("读取 runner 配置失败: " + file + ": " + e.getMessage(), e);
        }
        for (String name : p.stringPropertyNames()) {
            if (!name.startsWith("scan.") || !name.endsWith(".path")) {
                continue;
            }
            String engine = name.substring("scan.".length(), name.length() - ".path".length()).strip();
            if (!BINARIES.containsKey(engine)) {
                throw new IllegalArgumentException("scan." + engine + " 不是合法扫描引擎（可选 "
                        + String.join("/", BINARIES.keySet()) + "）: " + file);
            }
            String value = p.getProperty(name).strip();
            if (!value.isEmpty()) {
                out.put(engine, value);
            }
        }
        return out;
    }

    /**
     * 解析待 prepend 进 PATH 的目录：显式配置（文件→父目录，目录→自身）优先，
     * 空则 where/which 探测二进制；都找不到返回 null。
     */
    public static String resolveBinDir(Map<String, String> configured, String engine) {
        String binary = BINARIES.get(engine);
        if (binary == null) {
            return null;
        }
        String conf = configured.get(engine);
        if (conf != null && !conf.isBlank()) {
            Path p = Path.of(conf);
            if (Files.isDirectory(p)) {
                return p.toAbsolutePath().normalize().toString();
            }
            if (Files.isRegularFile(p) && p.getParent() != null) {
                return p.getParent().toAbsolutePath().normalize().toString();
            }
            return null; // 配了但路径不存在 → 当不可用，报错文案引导检查
        }
        return probe(binary);
    }

    public static String binaryName(String engine) {
        return BINARIES.get(engine);
    }

    public static Set<String> engines() {
        return BINARIES.keySet();
    }

    /** 平台探测：Windows=where / Linux·macOS=which（与 claudePath 空值探测同姿势） */
    private static String probe(String binary) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb = new ProcessBuilder(windows ? "where" : "which", binary);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            String first;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                first = reader.readLine();
            }
            int code = proc.waitFor();
            if (code != 0 || first == null || first.isBlank()) {
                return null;
            }
            Path found = Path.of(first.strip());
            return found.getParent() == null ? null
                    : found.getParent().toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return null;
        }
    }
}
