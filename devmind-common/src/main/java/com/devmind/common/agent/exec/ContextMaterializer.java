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
 * 落进会话工作区——CLAUDE.md（注入块在前，工作区既有内容以「项目原有 CLAUDE.md（保留）」节
 * 追加，不覆盖）、.claude/settings.local.json、.claude/skills/&lt;name&gt;/、.devmind/docs/&lt;docId&gt;.md。
 *
 * <p>防越界：skill 名/docId 走白名单，skill 文件相对路径拒绝绝对路径与 .. 逃逸。</p>
 */
public final class ContextMaterializer {

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
    }

    /** CLAUDE.md：注入块在前；既有内容（仓库自带）以保留节追加在后（沿用 KnowledgeInjector 时代结构）。 */
    private static void writeClaudeMd(Path workDir, String injection) throws IOException {
        if (injection == null || injection.isBlank()) {
            return;
        }
        Path file = workDir.resolve("CLAUDE.md");
        String orig = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        StringBuilder content = new StringBuilder(injection.strip()).append('\n');
        if (orig != null && !orig.isBlank()) {
            content.append("\n---\n\n## 项目原有 CLAUDE.md（保留）\n\n").append(orig.strip()).append('\n');
        }
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
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
