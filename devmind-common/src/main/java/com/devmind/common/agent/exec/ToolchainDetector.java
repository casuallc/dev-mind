package com.devmind.common.agent.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-34 FR-07 工具链探测：runner 启动时探测本机 java/mvn/node/npm/docker/git 的版本，
 * 随 hello 上报（{@code toolchain} 对象），供节点页展示与按标签调度参考。
 *
 * <p>每个工具独立 5s 超时，未安装/超时/解析失败即不进 map（宁缺毋滥，不阻塞启动）。
 * {@link Probe} 可注入便于测试。</p>
 */
public class ToolchainDetector {

    private static final Logger log = LoggerFactory.getLogger(ToolchainDetector.class);
    private static final long PROBE_TIMEOUT_MS = 5_000;

    /** 探测一个命令的版本输出；返回 null = 未安装/超时/失败。 */
    @FunctionalInterface
    public interface Probe {
        /** 执行 {@code cmd} 并返回合并输出（stdout+stderr，版本信息有的在 stderr），异常/超时返回 null。 */
        String run(List<String> cmd);
    }

    /** 探测目标：命令 + 版本解析正则（取首个 group）。 */
    private record Target(String key, List<String> cmd, Pattern versionPattern) {
    }

    private static final Pattern FIRST_VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    private static final List<Target> TARGETS = List.of(
            new Target("java", List.of("java", "-version"),
                    Pattern.compile("version \"([^\"]+)\"")),
            new Target("mvn", List.of("mvn", "-version"),
                    Pattern.compile("Apache Maven (\\S+)")),
            new Target("node", List.of("node", "--version"), FIRST_VERSION),
            new Target("npm", List.of("npm", "--version"), FIRST_VERSION),
            new Target("docker", List.of("docker", "--version"),
                    Pattern.compile("Docker version ([^,\\s]+)")),
            new Target("git", List.of("git", "--version"),
                    Pattern.compile("git version (\\S+)")));

    private final Probe probe;

    /** 生产用：真实起子进程探测。 */
    public ToolchainDetector() {
        this(ToolchainDetector::runProcess);
    }

    public ToolchainDetector(Probe probe) {
        this.probe = probe;
    }

    /** 探测全部目标 → {key: version}（未安装的不出现）。 */
    public Map<String, String> detect() {
        return detect(Map.of());
    }

    /**
     * CAP-106 FR-05：探测全部目标，{@code explicitPaths}（agent.properties 的
     * {@code toolchain.<key>}）命中的 key 先用显式路径探测（版本解析正则不变），失败回落
     * PATH 探测；显式探测成功的版本覆盖 PATH 结果。explicitPaths 里的未知 key 被忽略。
     */
    public Map<String, String> detect(Map<String, String> explicitPaths) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Target t : TARGETS) {
            String version = probeTarget(t, explicitPaths, Map.of());
            if (version != null) {
                out.put(t.key(), version);
            }
        }
        return out;
    }

    /**
     * CAP-106 FR-07：单目标执行期探测（程序包跑入口前按合并后 env 验证运行时真实可用）。
     * 解析顺序：① explicitPaths 显式路径；② envOverlay 的 PATH 手工扫目录（不依赖
     * ProcessBuilder 的平台相关查找语义，Windows CreateProcess 认父进程 PATH 不认子 env）；
     * ③ key=java 且 envOverlay 含 JAVA_HOME 时试 {@code $JAVA_HOME/bin/java}；
     * ④ 回落 PATH 探测（继承 runner 进程环境）。返回版本号，全程未命中返回 null。
     *
     * @param key 探测目标 key（java/mvn/node/npm/docker/git），未知 key 返回 null
     */
    public String detectOne(String key, Map<String, String> explicitPaths, Map<String, String> envOverlay) {
        Target t = target(key);
        if (t == null) {
            return null;
        }
        Map<String, String> overlay = envOverlay == null ? Map.of() : envOverlay;
        String version = probeTarget(t, explicitPaths, overlay);
        if (version != null) {
            return version;
        }
        if ("java".equals(key)) {
            String javaHome = overlay.get("JAVA_HOME");
            if (javaHome != null && !javaHome.isBlank()) {
                String bin = resolveExecutable(javaHome + java.io.File.separator + "bin", "java");
                if (bin != null) {
                    return parseVersion(probe.run(List.of(bin, "-version")), t.versionPattern());
                }
            }
        }
        return null;
    }

    /** 单个目标的完整探测链：显式路径 → envOverlay PATH 扫目录 → 裸命令 PATH 探测。 */
    private String probeTarget(Target t, Map<String, String> explicitPaths, Map<String, String> envOverlay) {
        String explicit = explicitPaths == null ? null : explicitPaths.get(t.key());
        if (explicit != null && !explicit.isBlank()) {
            String version = parseVersion(probe.run(explicitCmd(explicit, t.cmd())), t.versionPattern());
            if (version != null) {
                return version;
            }
        }
        String resolved = resolveOnOverlayPath(t.cmd().get(0), envOverlay);
        if (resolved != null) {
            String version = parseVersion(probe.run(replaceCmd0(t.cmd(), resolved)), t.versionPattern());
            if (version != null) {
                return version;
            }
        }
        return parseVersion(probe.run(t.cmd()), t.versionPattern());
    }

    /** 显式路径可指向可执行文件或 bin 目录：是目录时拼上命令名（Windows 补 .exe）。 */
    private static List<String> explicitCmd(String explicit, List<String> cmd) {
        java.io.File f = new java.io.File(explicit);
        String exe = f.isDirectory() ? resolveExecutable(explicit, cmd.get(0)) : explicit;
        return replaceCmd0(cmd, exe == null ? explicit : exe);
    }

    /** envOverlay 里找 PATH（大小写不敏感，Windows 常是 Path），逐目录找可执行文件。 */
    private static String resolveOnOverlayPath(String name, Map<String, String> envOverlay) {
        if (envOverlay == null || envOverlay.isEmpty()) {
            return null;
        }
        String pathEnv = null;
        for (Map.Entry<String, String> e : envOverlay.entrySet()) {
            if ("PATH".equalsIgnoreCase(e.getKey())) {
                pathEnv = e.getValue();
                break;
            }
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        for (String dir : pathEnv.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            String exe = resolveExecutable(dir, name);
            if (exe != null) {
                return exe;
            }
        }
        return null;
    }

    /** 在目录内找命令对应的可执行文件（Linux/macOS 原名；Windows 依次试 .exe/.cmd/.bat）。 */
    private static String resolveExecutable(String dir, String name) {
        java.io.File direct = new java.io.File(dir, name);
        if (direct.isFile() && direct.canExecute()) {
            return direct.getAbsolutePath();
        }
        if (isWindows()) {
            for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                java.io.File win = new java.io.File(dir, name + ext);
                if (win.isFile()) {
                    return win.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static List<String> replaceCmd0(List<String> cmd, String newCmd0) {
        List<String> out = new java.util.ArrayList<>(cmd);
        out.set(0, newCmd0);
        return out;
    }

    private static Target target(String key) {
        for (Target t : TARGETS) {
            if (t.key().equals(key)) {
                return t;
            }
        }
        return null;
    }

    /** 探测目标 key 全集（runner 侧校验 toolchain.&lt;key&gt; 配置合法性用）。 */
    public static List<String> targetKeys() {
        return TARGETS.stream().map(Target::key).toList();
    }

    /** 从版本输出中提取版本号（纯函数，可测）。输出为 null 或正则不命中返回 null。 */
    public static String parseVersion(String output, Pattern pattern) {
        if (output == null) {
            return null;
        }
        Matcher m = pattern.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    private static String runProcess(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // 限 5s：docker 等 CLI 在守护进程异常时可能挂起
            if (!p.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("工具链探测失败: {} ({})", cmd.get(0), e.getMessage());
            return null;
        }
    }
}
