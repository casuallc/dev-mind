package com.devmind.common.agent.exec;

import java.util.List;
import java.util.Map;

/**
 * CAP-34 FR-03 上下文包：数据所有权在服务端（知识/文档/技能均在服务端 DB），执行所有权在
 * runner——服务端把装配结果打包为本对象，runner 经 HTTP 拉取后由 {@link ContextMaterializer}
 * 物化到会话工作区。skill 可含二进制文件（base64），故走 HTTP JSON 而非 WS 帧。
 *
 * @param schemaVersion     包结构版本（当前 {@link #CURRENT_SCHEMA}）
 * @param claudeMd          CLAUDE.md 注入块（知识/场景装配结果；物化时既有内容追加保留）
 * @param settingsLocalJson .claude/settings.local.json 内容（权限白名单等服务端策略）
 * @param skills            技能包列表（P0 恒空，CAP-33 FR-04 填）
 * @param docs              文档全文列表（P0 恒空，CAP-33 FR-03 填）
 */
public record ContextPackage(int schemaVersion, String claudeMd, String settingsLocalJson,
                             List<SkillPackage> skills, List<DocEntry> docs) {

    public static final int CURRENT_SCHEMA = 1;

    /** 技能包：name = .claude/skills/&lt;name&gt;/ 目录名；files = 包内相对路径 → base64 内容（二进制安全）。 */
    public record SkillPackage(String name, Map<String, String> files) {
    }

    /** 文档全文（CAP-33 FR-03 两级投递的全文级）：物化为 .devmind/docs/&lt;docId&gt;.md。 */
    public record DocEntry(String docId, String title, String contentMd) {
    }

    /** 便捷构造：仅知识注入块 + settings（skills/docs 空表）。 */
    public static ContextPackage of(String claudeMd, String settingsLocalJson) {
        return new ContextPackage(CURRENT_SCHEMA, claudeMd, settingsLocalJson, List.of(), List.of());
    }
}
