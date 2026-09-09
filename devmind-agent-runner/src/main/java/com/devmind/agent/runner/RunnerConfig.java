package com.devmind.agent.runner;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * runner 配置（agent.properties，UTF-8）：
 * <pre>
 * serverUrl=ws://192.168.1.10:8080/ws/agent
 * token=dmag_xxx
 * claudePath=                # 空 = where claude 探测
 * permissionMode=acceptEdits # runner 默认权限模式（服务端指令未指定时用）
 * workDir=D:\devmind-work    # 项目无映射时的兜底工作目录
 * project.&lt;projectId&gt;=D:\repos\xxx   # 项目 → 节点本地路径映射（CAP-25 起仅作降级回退）
 * workspaceRoot=./workspaces # CAP-25 托管工作区根目录（收到带 repo 块的 launch 时启用：
 *                            # 克隆缓存 <root>/<projectId>/main + 会话 worktree <root>/<projectId>/sessions/<sid>）
 * gcDays=14                  # CAP-34 FR-05：会话目录超龄清理阈值（天）
 * gcIntervalMinutes=360      # GC 巡检间隔（分钟）
 * gcInitialDelayMinutes=10   # GC 启动后首跑延迟（分钟）
 * labels=windows,office      # CAP-34 FR-07：节点标签（CSV，hello 上报；非空覆盖服务端编辑值）
 * maxConcurrent=4
 * executor=claude            # claude=真实 CLI / fake=内置假进程（自测/无 claude 环境）
 * execAllowlist=mvn,./mvnw,npm,git   # CAP-36：exec 帧命令白名单（CSV 前缀；空=拒绝一切 exec。
 *                            # 逐行校验脚本每行首 token，shell 内置命令 echo/cd/if 等不受限）
 * execShell=bash             # CAP-36：exec 脚本解释器（命令写临时 .sh 后以其执行，同服务端 LocalStepRunner）
 * buildGcHours=24            # CAP-36：构建工作区（builds/）保留时长（小时），超龄 GC 删除
 * </pre>
 */
public record RunnerConfig(String serverUrl, String token, String claudePath, String permissionMode,
                           Path workDir, Map<String, Path> projectPaths, int maxConcurrent,
                           String executor, Path workspaceRoot, int gcDays, int gcIntervalMinutes,
                           int gcInitialDelayMinutes, java.util.List<String> labels,
                           java.util.List<String> execAllowlist, String execShell, int buildGcHours) {

    /** 兼容构造（FR-05 前的 9 参签名，测试/旧调用用）：GC 默认值 14 天 / 360 分钟 / 首跑 10 分钟，无标签。 */
    public RunnerConfig(String serverUrl, String token, String claudePath, String permissionMode,
                        Path workDir, Map<String, Path> projectPaths, int maxConcurrent,
                        String executor, Path workspaceRoot) {
        this(serverUrl, token, claudePath, permissionMode, workDir, projectPaths, maxConcurrent,
                executor, workspaceRoot, 14, 360, 10, java.util.List.of());
    }

    /** 兼容构造（CAP-36 前的 13 参签名）：exec 默认禁用（空白名单）+ bash + 构建区保留 24h。 */
    public RunnerConfig(String serverUrl, String token, String claudePath, String permissionMode,
                        Path workDir, Map<String, Path> projectPaths, int maxConcurrent,
                        String executor, Path workspaceRoot, int gcDays, int gcIntervalMinutes,
                        int gcInitialDelayMinutes, java.util.List<String> labels) {
        this(serverUrl, token, claudePath, permissionMode, workDir, projectPaths, maxConcurrent,
                executor, workspaceRoot, gcDays, gcIntervalMinutes, gcInitialDelayMinutes, labels,
                java.util.List.of(), "bash", 24);
    }

    public static RunnerConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStreamReader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        String serverUrl = required(p, "serverUrl", file);
        String token = required(p, "token", file);
        Map<String, Path> projects = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith("project.")) {
                projects.put(name.substring("project.".length()), Path.of(p.getProperty(name).strip()));
            }
        }
        return new RunnerConfig(
                serverUrl.strip(), token.strip(),
                p.getProperty("claudePath", "").strip(),
                p.getProperty("permissionMode", "acceptEdits").strip(),
                Path.of(p.getProperty("workDir", ".").strip()),
                projects,
                Integer.parseInt(p.getProperty("maxConcurrent", "4").strip()),
                p.getProperty("executor", "claude").strip(),
                Path.of(p.getProperty("workspaceRoot", "./workspaces").strip()),
                Integer.parseInt(p.getProperty("gcDays", "14").strip()),
                Integer.parseInt(p.getProperty("gcIntervalMinutes", "360").strip()),
                Integer.parseInt(p.getProperty("gcInitialDelayMinutes", "10").strip()),
                parseLabels(p.getProperty("labels", "")),
                parseLabels(p.getProperty("execAllowlist", "")),
                p.getProperty("execShell", "bash").strip(),
                Integer.parseInt(p.getProperty("buildGcHours", "24").strip()));
    }

    /** FR-07：labels CSV → 去空白去空项的 List（保序）。 */
    static java.util.List<String> parseLabels(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** 会话工作目录：项目映射优先，否则兜底 workDir。 */
    public Path resolveWorkDir(String projectId) {
        if (projectId != null && !projectId.isBlank() && projectPaths.containsKey(projectId)) {
            return projectPaths.get(projectId);
        }
        return workDir;
    }

    private static String required(Properties p, String key, Path file) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("配置缺少 " + key + ": " + file);
        }
        return v;
    }
}
