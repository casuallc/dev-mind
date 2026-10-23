package com.devmind.knowledge.dto;

import java.time.Instant;
import java.util.List;

/**
 * 知识条目视图。CAP-44 起 scope/projectId 由所属知识库派生（kbId 为一等归属），
 * 历史列仅作迁移期兜底；CAP-84 增加文档类型/来源/版本/治理字段。
 *
 * @param id             条目 ID
 * @param kbId           所属知识库 ID
 * @param scope          global | project（由 KB 派生）
 * @param projectId      项目范围所属项目（global 为 null，由 KB 派生）
 * @param name           名称
 * @param path           逻辑路径
 * @param contentMd      Markdown 内容
 * @param tags           标签
 * @param sourceProject  来源项目
 * @param hitCount       被注入次数
 * @param status         active | deprecated
 * @param source         manual | feishu | file
 * @param externalId     外部系统主键（飞书判重键；manual 为 null）
 * @param indexStatus    pending | ready | failed | disabled
 * @param indexError     最近索引失败原因
 * @param docType        TECH | MAINT | FAQ | ARTIFACT
 * @param sourceUri      原始相对路径/外部地址
 * @param mimeType       原始文件 MIME
 * @param summary        摘要
 * @param owner          负责人
 * @param contentVersion 内容版本号
 * @param validFrom      生效时间
 * @param validTo        失效时间
 * @param lastReviewedAt 最近复核时间
 * @param autoEnrich     是否允许模型自动补全分类/摘要/标签（人工修正后 false）
 * @param enrichedAt     最近一次模型补全成功时间
 * @param enrichError    最近一次模型补全失败原因
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
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
        String docType,
        String sourceUri,
        String mimeType,
        String summary,
        String owner,
        int contentVersion,
        Instant validFrom,
        Instant validTo,
        Instant lastReviewedAt,
        boolean autoEnrich,
        Instant enrichedAt,
        String enrichError,
        Instant createdAt,
        Instant updatedAt) {
}
