package com.devmind.knowledge.dto;

import java.time.Instant;
import java.util.List;

/**
 * 知识条目视图。CAP-44 起 scope/projectId 由所属知识库派生（kbId 为一等归属），
 * 历史列仅作迁移期兜底。
 *
 * @param id            条目 ID
 * @param kbId          所属知识库 ID
 * @param scope         global | project（由 KB 派生）
 * @param projectId     项目范围所属项目（global 为 null，由 KB 派生）
 * @param name          名称
 * @param path          逻辑路径
 * @param contentMd     Markdown 内容
 * @param tags          标签
 * @param sourceProject 来源项目
 * @param hitCount      被注入次数
 * @param status        active | deprecated
 * @param source        manual | feishu
 * @param externalId    外部系统主键（飞书判重键 {integrationId}:{docToken}；manual 为 null）
 * @param indexStatus   pending | ready | failed | disabled
 * @param indexError    最近索引失败原因
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record EntryView(
        Long id,
        Long kbId,
        String scope,
        String projectId,
        String name,
        String path,
        String contentMd,
        List<String> tags,
        String sourceProject,
        int hitCount,
        String status,
        String source,
        String externalId,
        String indexStatus,
        String indexError,
        Instant createdAt,
        Instant updatedAt) {
}
