package com.devmind.common.agent.exec;

import java.util.List;
import java.util.Map;

/**
 * CAP-34 FR-03 上下文包：数据所有权在服务端（知识/文档/技能均在服务端 DB），执行所有权在
 * runner——服务端把装配结果打包为本对象，runner 经 HTTP 拉取后由 {@link ContextMaterializer}
 * 物化到会话工作区。skill 可含二进制文件（base64），故走 HTTP JSON 而非 WS 帧。
 *
 * <p>schemaVersion：1 = CAP-34 原始结构；2 = 含 {@link #inputs}（CAP-40 需求附件投送）。
 * inputs 为空时仍发 1（存量 runner 无感）；非空发 2，老 runner 经 ContextPuller 版本门控
 * fail-visible，不静默丢附件。</p>
 *
 * @param schemaVersion     包结构版本（当前 {@link #CURRENT_SCHEMA}）
 * @param claudeMd          CLAUDE.md 注入块（知识/场景装配结果；物化时既有内容追加保留）
 * @param settingsLocalJson .claude/settings.local.json 内容（权限白名单等服务端策略）
 * @param skills            技能包列表（P0 恒空，CAP-33 FR-04 填）
 * @param docs              文档全文列表（CAP-33 FR-03）
 * @param inputs            需求附件投送列表（CAP-40），物化为 .devmind/input/&lt;path&gt;
 */
public record ContextPackage(int schemaVersion, String claudeMd, String settingsLocalJson,
                             List<SkillPackage> skills, List<DocEntry> docs,
                             List<InputFile> inputs) {

    public static final int CURRENT_SCHEMA = 2;

    /** 技能包：name = .claude/skills/&lt;name&gt;/ 目录名；files = 包内相对路径 → base64 内容（二进制安全）。 */
    public record SkillPackage(String name, Map<String, String> files) {
    }

    /** 文档全文（CAP-33 FR-03 两级投递的全文级）：物化为 .devmind/docs/&lt;docId&gt;.md。 */
    public record DocEntry(String docId, String title, String contentMd) {
    }

    /**
     * CAP-40 需求附件：path = 物化相对路径（白名单 [a-zA-Z0-9._-]，带来源/id 前缀防冲突），
     * base64 = 文件字节（二进制安全，同 skill 文件模式）。
     */
    public record InputFile(String path, String originalName, String contentType, String base64) {
    }

    /** 便捷构造：仅知识注入块 + settings（skills/docs/inputs 空表，schema 1）。 */
    public static ContextPackage of(String claudeMd, String settingsLocalJson) {
        return new ContextPackage(1, claudeMd, settingsLocalJson, List.of(), List.of(), List.of());
    }

    /** 全量构造：inputs 非空时 schema 2，否则 1。 */
    public static ContextPackage of(String claudeMd, String settingsLocalJson,
                                    List<SkillPackage> skills, List<DocEntry> docs,
                                    List<InputFile> inputs) {
        List<InputFile> safeInputs = inputs == null ? List.of() : inputs;
        return new ContextPackage(safeInputs.isEmpty() ? 1 : CURRENT_SCHEMA, claudeMd,
                settingsLocalJson, skills, docs, safeInputs);
    }
}
