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
        Map<String, String> out = new LinkedHashMap<>();
        for (Target t : TARGETS) {
            String output = probe.run(t.cmd());
            String version = parseVersion(output, t.versionPattern());
            if (version != null) {
                out.put(t.key(), version);
            }
        }
        return out;
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
