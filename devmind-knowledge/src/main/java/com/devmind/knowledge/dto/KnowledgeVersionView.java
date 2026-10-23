package com.devmind.knowledge.dto;

import java.time.Instant;

/**
 * CAP-84 FR-06 文档版本视图：列表只带元信息，正文经 {@code /versions/{version}} 按需读取。
 *
 * @param id             版本记录 ID
 * @param entryId        条目 ID
 * @param contentVersion 版本号
 * @param contentHash    正文哈希
 * @param sourceUri      当时的来源地址
 * @param changeNote     变更说明
 * @param createdBy      操作人
 * @param createdAt      记录时间
 */
public record KnowledgeVersionView(
        Long id,
        Long entryId,
        int contentVersion,
        String contentHash,
        String sourceUri,
        String changeNote,
        String createdBy,
        Instant createdAt) {

    /** 单版本正文详情。 */
    public record Detail(
            Long id,
            Long entryId,
            int contentVersion,
            String contentHash,
            String sourceUri,
            String changeNote,
            String createdBy,
            Instant createdAt,
            String contentMd) {
    }
}
