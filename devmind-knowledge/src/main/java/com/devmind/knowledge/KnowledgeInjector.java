package com.devmind.knowledge;

import com.devmind.project.model.Project;

/**
 * 知识注入 SPI（CAP-04）。会话启动前把全局+项目经验拼进 worktree 的 CLAUDE.md。
 * 实现：{@link KnowledgeBaseInjector}（从知识库选条目组装，CAP-04 落地后唯一实现）。
 */
public interface KnowledgeInjector {

    /**
     * 注入到 worktree。
     *
     * @param worktreePath worktree 绝对路径
     * @param project      项目
     * @param taskSpec     任务说明
     * @return 注入的 CLAUDE.md 全文（调试/审计用），未注入时返回空串
     */
    String apply(String worktreePath, Project project, String taskSpec);
}
