package com.devmind.common.agent.exec;

import java.util.List;

/**
 * CAP-33 FR-02 单个 {@link ContextProvider} 的装配产出。assembler 汇总各 provider 的
 * contribution 拼成最终 {@link ContextPackage} 与 manifest 快照。
 *
 * @param claudeMdSections  已渲染的 CLAUDE.md「## 节」片段（有序，assembler 按节序约定拼接）
 * @param skills            技能包（物化为 .claude/skills/&lt;name&gt;/）
 * @param docs              文档全文（物化为 .devmind/docs/&lt;docId&gt;.md）
 * @param settingsLocalJson .claude/settings.local.json 内容；契约：仅 knowledge provider 出，
 *                          assembler 取第一个非空
 * @param items             FR-07 可追溯清单（含 source 标注）
 */
public record ContextContribution(
        List<String> claudeMdSections,
        List<ContextPackage.SkillPackage> skills,
        List<ContextPackage.DocEntry> docs,
        String settingsLocalJson,
        List<ManifestItem> items) {

    /** 空产出（本 provider 无命中）。 */
    public static ContextContribution empty() {
        return new ContextContribution(List.of(), List.of(), List.of(), null, List.of());
    }
}
