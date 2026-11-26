package com.devmind.agent.runner;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * runner 运行身份（hello 上报，供节点页展示与「修改执行用户」引导）：
 * runUser = 节点侧 whoami；serviceMode ∈ windows-service / systemd / process；
 * serviceName = 服务/unit 名（env DEVMIND_RUNNER_SERVICE_NAME 或 agent.properties serviceName）；
 * workDir = runner 进程工作目录。全部尽力探测，失败项留空不报（服务端不动旧值）。
 */
public record RuntimeIdentity(String runUser, String serviceMode, String serviceName, String workDir) {

    /** 服务模式标记 env（一键安装脚本注入；WinSW xml / systemd unit 同款）。 */
    static final String ENV_SERVICE = "DEVMIND_RUNNER_SERVICE";
    static final String ENV_SERVICE_NAME = "DEVMIND_RUNNER_SERVICE_NAME";

    public static RuntimeIdentity detect(RunnerConfig config) {
        return detect(System.getenv(), System.getProperty("os.name", ""), config == null ? "" : config.serviceName());
    }

    /** 可测形态：env/osName/配置 serviceName 显式注入。 */
    static RuntimeIdentity detect(Map<String, String> env, String osName, String configServiceName) {
        boolean windows = osName.toLowerCase(java.util.Locale.ROOT).contains("windows");
        String mode;
        if (windows) {
            mode = "1".equals(env.get(ENV_SERVICE)) ? "windows-service" : "process";
        } else {
            // systemd 起的进程必有 INVOCATION_ID；否则按裸进程（nohup/cron/容器）
            String invocation = env.get("INVOCATION_ID");
            mode = invocation != null && !invocation.isBlank() ? "systemd" : "process";
        }
        String serviceName = env.get(ENV_SERVICE_NAME);
        if ((serviceName == null || serviceName.isBlank()) && configServiceName != null) {
            serviceName = configServiceName;
        }
        String workDir = System.getProperty("user.dir", "");
        return new RuntimeIdentity(whoami(), mode,
                serviceName == null ? "" : serviceName.strip(), workDir == null ? "" : workDir);
    }

    /** whoami 优先（服务场景 user.name 在 LocalSystem 下可能解析成机器账户），3s 超时，失败回落 user.name。 */
    static String whoami() {
        Process proc = null;
        try {
            proc = new ProcessBuilder("whoami").redirectErrorStream(true).start();
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return fallbackUser();
            }
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return out.isEmpty() ? fallbackUser() : out;
        } catch (Exception e) {
            return fallbackUser();
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    private static String fallbackUser() {
        return System.getProperty("user.name", "");
    }
}
