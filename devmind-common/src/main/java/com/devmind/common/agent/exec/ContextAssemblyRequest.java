package com.devmind.common.agent.exec;

import java.util.List;

/**
 * CAP-33 FR-02 上下文装配请求：assembler 三层合并（①场景显式绑定 ②项目自动命中
 * ③请求级追加）后分发给各 {@link ContextProvider}。场景组与请求组 ids 分列——
 * 去重在 provider 内做（只有 provider 知道哪些 id 有效/ACTIVE），并逐项标注
 * {@link ManifestItem#source}。
 *
 * <p>list 字段由 assembler 归一化为非 null（空 = {@link List#of()}）；projectId/projectTags
 * 对无项目问答可为 null/空。</p>
 *
 * @param projectId             装配上下文项目（session 的项目；chat 挂 PROJECT 场景时为场景项目）
 * @param projectTags           项目 tags（知识 ②层自动命中用）
 * @param scenarioSkillIds      ①场景绑定的 skill ids
 * @param extraSkillIds         ③请求级追加的 skill ids
 * @param scenarioDocIds        ①场景绑定的 doc ids
 * @param extraDocIds           ③请求级追加的 doc ids
 * @param scenarioKnowledgeTags ①场景绑定的知识 tags
 * @param extraKnowledgeTags    ③请求级追加的知识 tags
 * @param projectAuto           ②项目自动命中开关（知识按项目 tags 命中 + 项目私有条目/skill 全带）
 * @param dryRun                预览模式：不 bumpHits、无任何副作用
 */
public record ContextAssemblyRequest(
        String projectId, List<String> projectTags,
        List<String> scenarioSkillIds, List<String> extraSkillIds,
        List<Long> scenarioDocIds, List<Long> extraDocIds,
        List<String> scenarioKnowledgeTags, List<String> extraKnowledgeTags,
        boolean projectAuto, boolean dryRun) {
}
