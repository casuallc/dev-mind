package com.devmind.common.agent.exec;

import java.util.List;

/**
 * CAP-33 FR-02 上下文装配请求：assembler 把场景显式绑定、项目自动命中、请求级追加
 * 合并后分发给各 {@link ContextProvider}。去重在 provider 内做（只有 provider 知道哪些
 * id 有效/ACTIVE），并逐项标注 {@link ManifestItem#source}。
 *
 * <p>CAP-92 起知识消费统一走检索：场景绑定知识库 id（{@code scenarioKnowledgeBaseIds}），
 * 装配时以 {@code knowledgeQuery} 检索命中块注入；不再有按标签全量注入的 knowledgeTags。</p>
 *
 * <p>list 字段由 assembler 归一化为非 null（空 = {@link List#of()}）；projectId/projectTags
 * 对无项目问答可为 null/空。</p>
 *
 * @param projectId               装配上下文项目（session 的项目；chat 挂 PROJECT 场景时为场景项目）
 * @param projectTags             项目 tags（skill 项目自动命中用）
 * @param scenarioSkillIds        ①场景绑定的 skill ids
 * @param extraSkillIds           ③请求级追加的 skill ids
 * @param scenarioDocIds          ①场景绑定的 doc ids
 * @param extraDocIds             ③请求级追加的 doc ids
 * @param scenarioKnowledgeBaseIds ①场景绑定的知识库 ids（检索范围；空 = 不检索）
 * @param knowledgeQuery          本次装配的检索 query（渲染后的任务说明 / 问答首条消息）
 * @param projectAuto             ②项目自动命中开关（项目私有 skill 全带）
 * @param dryRun                  预览模式：不 bumpHits、无任何副作用
 * @param requirementId           会话关联需求（CAP-40 附件投送 provider 用；无关联需求/chat 为 null。
 *                                CAP-73 起 lean 会话也透传——附件投送改按 {@code lean} 标志自我豁免，
 *                                验证链触发助手（VerifyContextProvider）恰恰需要瘦上下文的执行会话
 *                                也能拿到需求绑定）
 * @param attachmentIds           CAP-68 创建会话即带附件（③请求级显式选择，不受瘦上下文影响；
 *                                会话附件 provider @Order 50 物化为 .devmind/input/attachments/）
 * @param sessionId               装配目标会话/chat id（CAP-73 verify token 绑定用；预览/chat 兼容路径为 null）
 * @param lean                    CAP-52 瘦上下文标志（执行会话）；provider 据此自我豁免需求级投送
 */
public record ContextAssemblyRequest(
        String projectId, List<String> projectTags,
        List<String> scenarioSkillIds, List<String> extraSkillIds,
        List<Long> scenarioDocIds, List<Long> extraDocIds,
        List<Long> scenarioKnowledgeBaseIds, String knowledgeQuery,
        boolean projectAuto, boolean dryRun, String requirementId,
        List<String> attachmentIds, String sessionId, boolean lean) {

    /** 兼容构造：无关联需求、无会话附件、无会话 id（chat/预览等既有调用点）。 */
    public ContextAssemblyRequest(
            String projectId, List<String> projectTags,
            List<String> scenarioSkillIds, List<String> extraSkillIds,
            List<Long> scenarioDocIds, List<Long> extraDocIds,
            List<Long> scenarioKnowledgeBaseIds, String knowledgeQuery,
            boolean projectAuto, boolean dryRun) {
        this(projectId, projectTags, scenarioSkillIds, extraSkillIds, scenarioDocIds, extraDocIds,
                scenarioKnowledgeBaseIds, knowledgeQuery, projectAuto, dryRun, null, null, null, false);
    }
}
