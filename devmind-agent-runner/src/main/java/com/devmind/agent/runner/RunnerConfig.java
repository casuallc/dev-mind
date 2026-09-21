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
 * claudeConfigDir=           # claude 配置目录（以 CLAUDE_CONFIG_DIR 注入 claude 子进程）。
 *                            # CAP-51 起空 = 平台专属目录 {workspaceRoot}/../claude-config（不再写节点用户的
 *                            # ~/.claude），使平台会话的 transcript 保留期与清理范围与个人记录隔离；
 *                            # 升级时需在该目录补一次登录态（settings.json/.credentials.json 或 ANTHROPIC_* env）。
 *                            # 服务化部署（LocalSystem/root）下务必显式指向已登录目录——
 *                            # 平台专属目录里的 settings.json 的 cleanupPeriodDays 由 runner 落 180 天
 *                            # （claude 默认 30 天会把静置超期的会话 transcript 扫掉、resume 失效）
 * permissionMode=acceptEdits # runner 默认权限模式（服务端指令未指定时用）
 * workDir=D:\devmind-work    # 项目无映射时的兜底工作目录
 * project.&lt;projectId&gt;=D:\repos\xxx   # 项目 → 节点本地路径映射（CAP-25 起仅作降级回退）
 * workspaceRoot=./workspaces # CAP-25 托管工作区根目录（收到带 repo 块的 launch 时启用）；
 *                            # CAP-42 起代码会话改为每用户固定布局：克隆缓存 <root>/<projectId>/<owner>/main
 *                            # + 固定 worktree <root>/<projectId>/<owner>/work（多库缓存 <owner>/<repoName>/main、
 *                            # 子 worktree work/<repoName>、聚合根 work/ 作 claude cwd）；结束不删，页面手动收口。
 *                            # CAP-51 起带 workspaceKey 的会话改落 worktrees/<key>（需求内多会话共用一棵工作树，
 *                            # 收口保留工作树并前进到新基线），无 key 的存量会话仍走 work/ 旧布局。
 *                            # 构建工作区仍为共享 <root>/<projectId>/{main,builds}（owner 保留名防撞）；_chat 布局不变。
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
 * worklogRoot=               # CAP-41：工作日志持久工作区根目录（kind:"worklog" 会话启用：
 *                            # <root>/<console-username>/ 本地 git 仓库，永不删除、不参与 GC）。
 *                            # 空 = {user.home}/worklog
 * partialMessages=true       # CAP-50：给 claude 加 --include-partial-messages 出逐 token 打字机效果。
 *                            # 需 claude 认识该参数（2.1.250+ 实测可用）；节点上版本过旧时未知选项会让
 *                            # claude 非零退出、会话直接 FAILED，此时改 false 重启 runner 即退回整块输出
 * worktreeGcDays=30          # CAP-51：需求粒度工作树（worktrees/&lt;key&gt;）超龄回收阈值（天）。
 *                            # 比会话目录 gcDays 长——需求生命周期更长；未提交改动 / 分支未推远端永不删。
 *                            # 应与 claude 侧 transcript 保留期（cleanupPeriodDays，runner 落 180 天）
 *                            # 对齐：工作树还在而会话续不上是最难排查的半可用状态
 * </pre>
 */
public record RunnerConfig(String serverUrl, String token, String claudePath, String permissionMode,
                           Path workDir, Map<String, Path> projectPaths, int maxConcurrent,
                           String executor, Path workspaceRoot, int gcDays, int gcIntervalMinutes,
                           int gcInitialDelayMinutes, java.util.List<String> labels,
                           java.util.List<String> execAllowlist, String execShell, int buildGcHours,
                           String claudeConfigDir, String worklogRoot, boolean partialMessages,
                           int worktreeGcDays) {

    /** 兼容构造（CAP-51 前的 19 参签名）：需求工作树 GC 阈值默认 30 天。 */
    public RunnerConfig(String serverUrl, String token, String claudePath, String permissionMode,
                        Path workDir, Map<String, Path> projectPaths, int maxConcurrent,
                        String executor, Path workspaceRoot, int gcDays, int gcIntervalMinutes,
                        int gcInitialDelayMinutes, java.util.List<String> labels,
                        java.util.List<String> execAllowlist, String execShell, int buildGcHours,
                        String claudeConfigDir, String worklogRoot, boolean partialMessages) {
        this(serverUrl, token, claudePath, permissionMode, workDir, projectPaths, maxConcurrent,
                executor, workspaceRoot, gcDays, gcIntervalMinutes, gcInitialDelayMinutes, labels,
                execAllowlist, execShell, buildGcHours, claudeConfigDir, worklogRoot, partialMessages,
                30);
    }

    /** 兼容构造（CAP-50 前的 18 参签名）：partial messages 默认开启。 */
    public RunnerConfig(String serverUrl, String token, String claudePath, String permissionMode,
                        Path workDir, Map<String, Path> projectPaths, int maxConcurrent,
                        String executor, Path workspaceRoot, int gcDays, int gcIntervalMinutes,
                        int gcInitialDelayMinutes, java.util.List<String> labels,
                        java.util.List<String> execAllowlist, String execShell, int buildGcHours,
                        String claudeConfigDir, String worklogRoot) {
        this(serverUrl, token, claudePath, permissionMode, workDir, projectPaths, maxConcurrent,
                executor, workspaceRoot, gcDays, gcIntervalMinutes, gcInitialDelayMinutes, labels,
                execAllowlist, execShell, buildGcHours, claudeConfigDir, worklogRoot, true);
    }

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

    /** 兼容构造（claudeConfigDir 前的 16 参签名）：claude 配置目录默认空（用 claude 默认 ~/.claude）。 */
    public RunnerConfig(String serverUrl, String token, String claudePath, String permissionMode,
                        Path workDir, Map<String, Path> projectPaths, int maxConcurrent,
                        String executor, Path workspaceRoot, int gcDays, int gcIntervalMinutes,
                        int gcInitialDelayMinutes, java.util.List<String> labels,
                        java.util.List<String> execAllowlist, String execShell, int buildGcHours) {
        this(serverUrl, token, claudePath, permissionMode, workDir, projectPaths, maxConcurrent,
                executor, workspaceRoot, gcDays, gcIntervalMinutes, gcInitialDelayMinutes, labels,
                execAllowlist, execShell, buildGcHours, "");
    }

    /** 兼容构造（CAP-41 前的 17 参签名）：worklogRoot 默认空（{user.home}/worklog）。 */
    public RunnerConfig(String serverUrl, String token, String claudePath, String permissionMode,
                        Path workDir, Map<String, Path> projectPaths, int maxConcurrent,
                        String executor, Path workspaceRoot, int gcDays, int gcIntervalMinutes,
                        int gcInitialDelayMinutes, java.util.List<String> labels,
                        java.util.List<String> execAllowlist, String execShell, int buildGcHours,
                        String claudeConfigDir) {
        this(serverUrl, token, claudePath, permissionMode, workDir, projectPaths, maxConcurrent,
                executor, workspaceRoot, gcDays, gcIntervalMinutes, gcInitialDelayMinutes, labels,
                execAllowlist, execShell, buildGcHours, claudeConfigDir, "");
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
                Integer.parseInt(p.getProperty("buildGcHours", "24").strip()),
                p.getProperty("claudeConfigDir", "").strip(),
                p.getProperty("worklogRoot", "").strip(),
                !"false".equalsIgnoreCase(p.getProperty("partialMessages", "true").strip()),
                Integer.parseInt(p.getProperty("worktreeGcDays", "30").strip()));
    }

    /** CAP-41：worklog 持久工作区根目录——配置优先，空 = {user.home}/worklog。 */
    public Path resolvedWorklogRoot() {
        if (worklogRoot != null && !worklogRoot.isBlank()) {
            return Path.of(worklogRoot).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), "worklog").toAbsolutePath().normalize();
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
