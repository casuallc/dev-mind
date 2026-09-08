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
    }
}
