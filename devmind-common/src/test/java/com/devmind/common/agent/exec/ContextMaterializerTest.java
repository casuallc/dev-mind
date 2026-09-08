package com.devmind.common.agent.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ContextMaterializer} 物化语义：CLAUDE.md 合并保留、settings 落盘、skills/docs 路径安全。 */
class ContextMaterializerTest {

    @TempDir
    Path workDir;

    @Test
    void materializesClaudeMdIntoEmptyWorkDir() throws Exception {
        ContextMaterializer.materialize(workDir, ContextPackage.of("<!-- 注入块 -->\n\n## 通用经验\n", SETTINGS_JSON));
        assertEquals("<!-- 注入块 -->\n\n## 通用经验\n",
                Files.readString(workDir.resolve("CLAUDE.md"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(workDir.resolve(".claude").resolve("settings.local.json")));
    }

    @Test
    void keepsExistingClaudeMdAsPreservedSection() throws Exception {
        Files.writeString(workDir.resolve("CLAUDE.md"), "# 项目自有说明\n", StandardCharsets.UTF_8);
        ContextMaterializer.materialize(workDir, ContextPackage.of("## 通用经验\n", null));
        String merged = Files.readString(workDir.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(merged.startsWith("## 通用经验"), merged);
        assertTrue(merged.contains("## 项目原有 CLAUDE.md（保留）"), merged);
        assertTrue(merged.contains("# 项目自有说明"), merged);
        // 无 settings 内容 → 不写文件
        assertTrue(Files.notExists(workDir.resolve(".claude").resolve("settings.local.json")));
    }

    @Test
    void blankPackageWritesNothing() throws Exception {
        ContextMaterializer.materialize(workDir, ContextPackage.of(" ", ""));
        assertTrue(Files.notExists(workDir.resolve("CLAUDE.md")));
        assertTrue(Files.notExists(workDir.resolve(".claude")));
    }

    @Test
    void materializesSkillsAndDocs() throws Exception {
        byte[] bin = {0x1, 0x2, (byte) 0xFF};
        ContextPackage pkg = new ContextPackage(ContextPackage.CURRENT_SCHEMA, null, null,
                List.of(new ContextPackage.SkillPackage("review", Map.of(
                        "SKILL.md", Base64.getEncoder().encodeToString("# 评审\n".getBytes(StandardCharsets.UTF_8)),
                        "bin/tool.bin", Base64.getEncoder().encodeToString(bin)))),
                List.of(new ContextPackage.DocEntry("d1", "方案", "正文")));
        ContextMaterializer.materialize(workDir, pkg);
        assertEquals("# 评审\n", Files.readString(
                workDir.resolve(".claude/skills/review/SKILL.md"), StandardCharsets.UTF_8));
        assertEquals(0xFF, Files.readAllBytes(workDir.resolve(".claude/skills/review/bin/tool.bin"))[2] & 0xFF);
        String doc = Files.readString(workDir.resolve(".devmind/docs/d1.md"), StandardCharsets.UTF_8);
        assertTrue(doc.startsWith("# 方案"), doc);
    }

    @Test
    void rejectsUnsafeSkillPaths() {
        ContextPackage badName = new ContextPackage(1, null, null,
                List.of(new ContextPackage.SkillPackage("../evil", Map.of())), List.of());
        assertThrows(IllegalArgumentException.class, () -> ContextMaterializer.materialize(workDir, badName));
        ContextPackage badFile = new ContextPackage(1, null, null,
                List.of(new ContextPackage.SkillPackage("ok", Map.of("../../escape.txt", "eA=="))), List.of());
        assertThrows(IllegalArgumentException.class, () -> ContextMaterializer.materialize(workDir, badFile));
    }

    @Test
    void manifestMatchesJsonBytes() {
        ContextPackage pkg = ContextPackage.of("md", "json");
        byte[] bytes = ContextPackages.toJsonBytes(pkg);
        ContextManifest m = ContextPackages.manifestOf(bytes, 3);
        assertEquals(3, m.entries());
        assertEquals(bytes.length, m.totalBytes());
        assertEquals(ContextPackages.sha256Hex(bytes), m.sha256());
        // 往返序列化
        assertEquals(pkg, ContextPackages.fromJson(bytes));
    }

    private static final String SETTINGS_JSON = "{\n  \"permissions\": {\"allow\": [\"Read\"]}\n}\n";
}
