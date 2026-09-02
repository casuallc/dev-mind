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
 * project.&lt;projectId&gt;=D:\repos\xxx   # 项目 → 节点本地路径映射
 * maxConcurrent=4
 * executor=claude            # claude=真实 CLI / fake=内置假进程（自测/无 claude 环境）
 * </pre>
 */
public record RunnerConfig(String serverUrl, String token, String claudePath, String permissionMode,
                           Path workDir, Map<String, Path> projectPaths, int maxConcurrent,
                           String executor) {

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
                p.getProperty("executor", "claude").strip());
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
