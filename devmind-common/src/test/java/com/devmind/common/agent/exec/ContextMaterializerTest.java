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

/** {@link ContextMaterializer} 物化语义：注入块落 CLAUDE.local.md 且不碰仓库 CLAUDE.md、
 *  settings 落盘、skills/docs 路径安全。 */
class ContextMaterializerTest {

    @TempDir
    Path workDir;

    @Test
    void materializesInjectionIntoLocalClaudeMd() throws Exception {
        ContextMaterializer.materialize(workDir, ContextPackage.of("<!-- 注入块 -->\n\n## 通用经验\n", SETTINGS_JSON));
        assertEquals("<!-- 注入块 -->\n\n## 通用经验\n",
                Files.readString(workDir.resolve(ContextMaterializer.INJECTION_FILE), StandardCharsets.UTF_8));
        // 仓库自带的 CLAUDE.md 不落文件、不被改写（claude 自己会读它）
        assertTrue(Files.notExists(workDir.resolve("CLAUDE.md")));
        assertTrue(Files.isRegularFile(workDir.resolve(".claude").resolve("settings.local.json")));
    }

    @Test
    void keepsRepoClaudeMdUntouched() throws Exception {
        Files.writeString(workDir.resolve("CLAUDE.md"), "# 项目自有说明\n", StandardCharsets.UTF_8);
        ContextMaterializer.materialize(workDir, ContextPackage.of("## 通用经验\n", null));
        // 仓库 CLAUDE.md 一字节不动：会话 worktree 对 git 恒净，收口脏检查只反映真实改动
        assertEquals("# 项目自有说明\n",
                Files.readString(workDir.resolve("CLAUDE.md"), StandardCharsets.UTF_8));
        String injected = Files.readString(workDir.resolve(ContextMaterializer.INJECTION_FILE),
                StandardCharsets.UTF_8);
        assertEquals("## 通用经验\n", injected);
        assertTrue(!injected.contains("项目自有说明"), injected);
        // 无 settings 内容 → 不写文件
        assertTrue(Files.notExists(workDir.resolve(".claude").resolve("settings.local.json")));
    }

    @Test
    void relaunchOverwritesInjectionInsteadOfStacking() throws Exception {
        ContextMaterializer.materialize(workDir, ContextPackage.of("## 第一轮任务\n", null));
        ContextMaterializer.materialize(workDir, ContextPackage.of("## 第二轮任务\n", null));
        // 续接/重发 launch 整文件覆盖：旧注入不残留（旧实现会把它当「项目原有」再追加一层）
        assertEquals("## 第二轮任务\n",
                Files.readString(workDir.resolve(ContextMaterializer.INJECTION_FILE), StandardCharsets.UTF_8));
    }

    @Test
    void blankPackageWritesNothing() throws Exception {
        ContextMaterializer.materialize(workDir, ContextPackage.of(" ", ""));
        assertTrue(Files.notExists(workDir.resolve(ContextMaterializer.INJECTION_FILE)));
        assertTrue(Files.notExists(workDir.resolve(".claude")));
    }

    @Test
    void materializesSkillsAndDocs() throws Exception {
        byte[] bin = {0x1, 0x2, (byte) 0xFF};
        ContextPackage pkg = new ContextPackage(ContextPackage.CURRENT_SCHEMA, null, null,
                List.of(new ContextPackage.SkillPackage("review", Map.of(
                        "SKILL.md", Base64.getEncoder().encodeToString("# 评审\n".getBytes(StandardCharsets.UTF_8)),
                        "bin/tool.bin", Base64.getEncoder().encodeToString(bin)))),
                List.of(new ContextPackage.DocEntry("d1", "方案", "正文")), List.of());
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
                List.of(new ContextPackage.SkillPackage("../evil", Map.of())), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> ContextMaterializer.materialize(workDir, badName));
        ContextPackage badFile = new ContextPackage(1, null, null,
                List.of(new ContextPackage.SkillPackage("ok", Map.of("../../escape.txt", "eA=="))), List.of(),
                List.of());
        assertThrows(IllegalArgumentException.class, () -> ContextMaterializer.materialize(workDir, badFile));
    }

    @Test
    void materializesInputsAndRejectsUnsafePaths() throws Exception {
        byte[] png = {1, 2, 3};
        ContextPackage pkg = ContextPackage.of(null, null, List.of(), List.of(),
                List.of(new ContextPackage.InputFile("abc123-shot.png", "shot.png", "image/png",
                        Base64.getEncoder().encodeToString(png))));
        // inputs 非空 → schema 2
        assertEquals(2, pkg.schemaVersion());
        ContextMaterializer.materialize(workDir, pkg);
        assertEquals(3, Files.readAllBytes(workDir.resolve(".devmind/input/abc123-shot.png")).length);
        // 空 inputs → schema 1（存量 runner 无感）
        assertEquals(1, ContextPackage.of("md", null, List.of(), List.of(), List.of()).schemaVersion());
        // 非法附件路径（白名单外字符）→ 拒绝
        ContextPackage bad = new ContextPackage(2, null, null, List.of(), List.of(),
                List.of(new ContextPackage.InputFile("../evil.png", "evil.png", "image/png", "eA==")));
        assertThrows(IllegalArgumentException.class, () -> ContextMaterializer.materialize(workDir, bad));
    }

    @Test
    void splitMaterializationSeparatesSharedAndSessionParts() throws Exception {
        // CAP-53：settings/skills 落 cwd（共享同构），注入块/docs/inputs 落代码目录（会话特定）
        Path cwd = workDir.resolve("cwd");
        Path code = workDir.resolve("code");
        ContextPackage pkg = new ContextPackage(ContextPackage.CURRENT_SCHEMA, "## 任务\n", SETTINGS_JSON,
                List.of(new ContextPackage.SkillPackage("review", Map.of("SKILL.md",
                        Base64.getEncoder().encodeToString("# 评审\n".getBytes(StandardCharsets.UTF_8))))),
                List.of(new ContextPackage.DocEntry("d1", "方案", "正文")),
                List.of(new ContextPackage.InputFile("abc123-shot.png", "shot.png", "image/png",
                        Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))));
        ContextMaterializer.materializeShared(cwd, pkg);
        ContextMaterializer.materializeSession(code, pkg);

        assertTrue(Files.isRegularFile(cwd.resolve(".claude/settings.local.json")));
        assertTrue(Files.isRegularFile(cwd.resolve(".claude/skills/review/SKILL.md")));
        assertTrue(Files.notExists(cwd.resolve(ContextMaterializer.INJECTION_FILE)), "cwd 不得落会话注入块");
        assertTrue(Files.notExists(cwd.resolve(".devmind")), "cwd 不得落 docs/inputs");

        assertEquals("## 任务\n", Files.readString(code.resolve(ContextMaterializer.INJECTION_FILE),
                StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(code.resolve(".devmind/docs/d1.md")));
        assertTrue(Files.isRegularFile(code.resolve(".devmind/input/abc123-shot.png")));
        assertTrue(Files.notExists(code.resolve(".claude")), "代码目录不得落 settings/skills");
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
