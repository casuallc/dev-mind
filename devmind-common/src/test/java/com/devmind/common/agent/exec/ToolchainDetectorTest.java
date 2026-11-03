package com.devmind.common.agent.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-34 FR-07 {@link ToolchainDetector}：假 Probe 测版本解析与缺装跳过；真 git 冒烟。 */
class ToolchainDetectorTest {

    @Test
    void 假Probe解析各工具版本() {
        Map<String, String> out = new ToolchainDetector(cmd -> switch (cmd.get(0)) {
            case "java" -> "openjdk version \"21.0.2\" 2024-01-16";
            case "mvn" -> "Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)";
            case "node" -> "v22.11.0";
            case "npm" -> "10.9.0";
            case "docker" -> "Docker version 27.3.1, build ce12230";
            case "git" -> "git version 2.47.0.windows.1";
            default -> null;
        }).detect();

        assertEquals("21.0.2", out.get("java"));
        assertEquals("3.9.9", out.get("mvn"));
        assertEquals("22.11.0", out.get("node"));
        assertEquals("10.9.0", out.get("npm"));
        assertEquals("27.3.1", out.get("docker"));
        assertEquals("2.47.0.windows.1", out.get("git"));
    }

    @Test
    void 未安装或解析失败不进map() {
        Map<String, String> out = new ToolchainDetector(cmd ->
                "git".equals(cmd.get(0)) ? "git version 2.47.0" : null).detect();
        assertEquals(Map.of("git", "2.47.0"), out);

        // 输出乱码/非版本格式 → 跳过
        assertTrue(new ToolchainDetector(cmd -> "command not found").detect().isEmpty());
    }

    @Test
    void parseVersion空输出返回null() {
        assertNull(ToolchainDetector.parseVersion(null, Pattern.compile("(\\d+)")));
        assertNull(ToolchainDetector.parseVersion("no digits here", Pattern.compile("(\\d+\\.\\d+)")));
    }

    /** 冒烟：真实 git 必然存在于开发/CI 环境（本仓库测试本身就依赖它）。 */
    @Test
    void 真实git探测冒烟() {
        ToolchainDetector.Probe real = cmd -> {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                if (p.waitFor() != 0) {
                    return null;
                }
                return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        };
        String gitVersion = new ToolchainDetector(real).detect().get("git");
        assertTrue(gitVersion != null && gitVersion.matches("\\d+.*"),
                "真实 git 应探测出版本号: " + gitVersion);
        assertFalse(gitVersion.isBlank());
    }

    @Test
    void 探测目标覆盖六工具() {
        // 防空实现也应产出 6 次调用（每目标一次），验证目标清单完整
        List<String> probed = new java.util.ArrayList<>();
        new ToolchainDetector(cmd -> {
            probed.add(cmd.get(0));
            return null;
        }).detect();
        assertEquals(List.of("java", "mvn", "node", "npm", "docker", "git"), probed);
        assertEquals(probed, ToolchainDetector.targetKeys());
    }

    // ---- CAP-106 FR-05/FR-07 ----

    @Test
    void 显式路径优先且版本覆盖PATH结果() {
        Map<String, String> out = new ToolchainDetector(cmd -> switch (cmd.get(0)) {
            case "/opt/jdk21/bin/java" -> "openjdk version \"21.0.7\" 2025-04-15";
            case "java" -> "openjdk version \"17.0.9\" 2023-10-17";
            default -> null;
        }).detect(Map.of("java", "/opt/jdk21/bin/java"));

        assertEquals("21.0.7", out.get("java"), "显式路径探测成功的版本须覆盖 PATH 探测结果");
    }

    @Test
    void 显式路径探测失败回落PATH() {
        Map<String, String> out = new ToolchainDetector(cmd ->
                "java".equals(cmd.get(0)) ? "openjdk version \"17.0.9\" 2023-10-17" : null)
                .detect(Map.of("java", "/nonexistent/bin/java"));

        assertEquals("17.0.9", out.get("java"), "显式路径失败须回落 PATH 探测");
    }

    @Test
    void detectOne未知key返回null() {
        assertNull(new ToolchainDetector(cmd -> "anything").detectOne("python", Map.of(), Map.of()));
    }

    @Test
    void detectOne显式路径优先于PATH覆盖(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        java.nio.file.Path bin = java.nio.file.Files.createDirectory(tmp.resolve("bin"));
        java.nio.file.Path javaExe = bin.resolve("java");
        java.nio.file.Files.writeString(javaExe, "#!/bin/sh\n");
        javaExe.toFile().setExecutable(true);

        String version = new ToolchainDetector(cmd ->
                cmd.get(0).equals(javaExe.toFile().getAbsolutePath())
                        ? "openjdk version \"21.0.7\" 2025-04-15" : null)
                .detectOne("java", Map.of(), Map.of("PATH", bin.toString()));
        assertEquals("21.0.7", version, "envOverlay PATH 扫到可执行文件后须以绝对路径探测");
    }

    @Test
    void detectOneJava回落JAVA_HOME(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        java.nio.file.Path bin = java.nio.file.Files.createDirectories(tmp.resolve("jdk/bin"));
        java.nio.file.Path javaExe = bin.resolve("java");
        java.nio.file.Files.writeString(javaExe, "#!/bin/sh\n");
        javaExe.toFile().setExecutable(true);

        String version = new ToolchainDetector(cmd ->
                cmd.get(0).equals(javaExe.toFile().getAbsolutePath())
                        ? "openjdk version \"21.0.7\" 2025-04-15" : null)
                .detectOne("java", Map.of(), Map.of("JAVA_HOME", tmp.resolve("jdk").toString()));
        assertEquals("21.0.7", version, "PATH 无覆盖时须尝试 JAVA_HOME/bin/java");
    }

    @Test
    void detectOne全程未命中返回null() {
        assertNull(new ToolchainDetector(cmd -> null)
                .detectOne("java", Map.of("java", "/nonexistent/bin/java"),
                        Map.of("PATH", "/nonexistent", "JAVA_HOME", "/nonexistent")));
    }
}
