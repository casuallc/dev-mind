package com.devmind.knowledge;

import com.devmind.project.model.Project;

/**
 * 知识注入 SPI（CAP-04）。CAP-34 起职责收敛为「服务端装配」：从知识库选全局+项目经验，
 * 组装 CLAUDE.md 注入块与 settings.local.json 内容；物化（写文件）移交 runner 侧
 * devmind-common {@code agent.exec} 的 ContextMaterializer。
 * 实现：{@link KnowledgeBaseInjector}（从知识库选条目组装，CAP-04 落地后唯一实现）。
 */
public interface KnowledgeInjector {

    /**
     * 装配注入内容。
     *
     * @param project  项目（可 null = 无项目会话，仅全局条目按标签匹配）
     * @param taskSpec 任务说明
     * @return 注入包；未启用或无命中条目时返回 null
     */
    InjectionPackage build(Project project, String taskSpec);

    /**
     * 注入包：CLAUDE.md 注入块 + settings.local.json 内容 + 命中条目数（manifest 统计用）。
     * 物化时工作区既有 CLAUDE.md 由 runner 侧以保留节追加，此处不含。
     */
    record InjectionPackage(String claudeMd, String settingsLocalJson, int entryCount) {
    }
}
