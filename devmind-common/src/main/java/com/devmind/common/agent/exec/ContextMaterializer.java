package com.devmind.common.agent.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CAP-34 FR-01/03 上下文物化器（只被 runner 使用，服务端不引用）：把 {@link ContextPackage}
 * 落进会话工作区——{@value #INJECTION_FILE}（整文件覆盖，注入块首行标记来自服务端装配器）、
 * .claude/settings.local.json、.claude/skills/&lt;name&gt;/、.devmind/docs/&lt;docId&gt;.md。
 *
 * <p><b>落点全部是平台托管路径</b>（{@link RunnerWorkspace#excludePlatformPaths} 写进克隆缓存
 * info/exclude）：不改写仓库被跟踪的文件、不进版本控制，否则会话 worktree 恒脏，收口
 * 「未提交改动」检查必失败（CAP-42 事故：干净会话也收不了口）；也防 agent「git add -A」
 * 把物化文件提交进会话分支后合入基线。</p>
 *
 * <p>防越界：skill 名/docId 走白名单，skill 文件相对路径拒绝绝对路径与 .. 逃逸。</p>
 */
public final class ContextMaterializer {

    /**
     * 注入块落点：claude CLI 的 <b>Local 作用域</b>指令文件（与 CLAUDE.md 同一套向上查找、
     * 随 cwd 自动加载，官方约定「不入库」）。选它而非 CLAUDE.md：仓库自带的 CLAUDE.md
     * 由 claude 自己读，平台无需内联改写——写 CLAUDE.md 会让被跟踪文件恒脏，且续接时
     * 上次注入内容会被当成「项目原有」再追加一层。
     */
    public static final String INJECTION_FILE = "CLAUDE.local.md";

    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9._-]+");

    private ContextMaterializer() {
    }

    public static void materialize(Path workDir, ContextPackage pkg) throws IOException {
        if (pkg == null) {
            return;
        }
        writeClaudeMd(workDir, pkg.claudeMd());
        writeSettingsLocal(workDir, pkg.settingsLocalJson());
        for (ContextPackage.SkillPackage skill : pkg.skills() == null ? List.<ContextPackage.SkillPackage>of() : pkg.skills()) {
            writeSkill(workDir, skill);
        }
        for (ContextPackage.DocEntry doc : pkg.docs() == null ? List.<ContextPackage.DocEntry>of() : pkg.docs()) {
            writeDoc(workDir, doc);
        }
        for (ContextPackage.InputFile input : pkg.inputs() == null ? List.<ContextPackage.InputFile>of() : pkg.inputs()) {
            writeInput(workDir, input);
        }
    }

    /** CAP-40 需求附件：物化为 .devmind/input/<path>（path 白名单 + 越界校验，同 skill 文件）。 */
    private static void writeInput(Path workDir, ContextPackage.InputFile input) throws IOException {
        requireSafe(input.path(), "附件路径");
        Path dir = workDir.resolve(".devmind").resolve("input");
        Path target = dir.resolve(input.path()).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("附件路径越界: " + input.path());
        }
        Files.createDirectories(dir);
        Files.write(target, Base64.getDecoder().decode(input.base64()));
    }

    /**
     * 注入块整文件覆盖 {@value #INJECTION_FILE}（每次 launch 唯一内容，不追加、不读回既有内容：
     * 续接时旧注入不残留、仓库自带 CLAUDE.md 一字节不动）。
     */
    private static void writeClaudeMd(Path workDir, String injection) throws IOException {
        if (injection == null || injection.isBlank()) {
            return;
        }
        Files.writeString(workDir.resolve(INJECTION_FILE),
                injection.strip() + "\n", StandardCharsets.UTF_8);
    }

    private static void writeSettingsLocal(Path workDir, String json) throws IOException {
        if (json == null || json.isBlank()) {
            return;
        }
        Path dir = workDir.resolve(".claude");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.local.json"), json, StandardCharsets.UTF_8);
    }

    private static void writeSkill(Path workDir, ContextPackage.SkillPackage skill) throws IOException {
        requireSafe(skill.name(), "skill 名");
        Path root = workDir.resolve(".claude").resolve("skills").resolve(skill.name()).normalize();
        if (!root.startsWith(workDir)) {
            throw new IllegalArgumentException("skill 路径越界: " + skill.name());
        }
        if (skill.files() == null) {
            return;
        }
        for (var e : skill.files().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || Path.of(e.getKey()).isAbsolute()
                    || e.getKey().contains("..")) {
                throw new IllegalArgumentException("skill 文件路径非法（拒绝绝对路径与 ..）: " + e.getKey());
            }
            Path target = root.resolve(e.getKey()).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("skill 文件路径越界: " + e.getKey());
            }
            Files.createDirectories(target.getParent());
            Files.write(target, Base64.getDecoder().decode(e.getValue()));
        }
    }

    private static void writeDoc(Path workDir, ContextPackage.DocEntry doc) throws IOException {
        requireSafe(doc.docId(), "docId");
        Path dir = workDir.resolve(".devmind").resolve("docs");
        Files.createDirectories(dir);
        String body = "# " + (doc.title() == null || doc.title().isBlank() ? doc.docId() : doc.title())
                + "\n\n" + (doc.contentMd() == null ? "" : doc.contentMd());
        Files.writeString(dir.resolve(doc.docId() + ".md"), body, StandardCharsets.UTF_8);
    }

    private static void requireSafe(String name, String what) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法" + what + "（白名单 [a-zA-Z0-9._-]）: " + name);
        }
    }
}
