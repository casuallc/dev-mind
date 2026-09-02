package com.devmind.skill.dto;

import java.time.Instant;

/** 附件元数据视图（列表不读内容 Lob，内容走 /files/{fileId} 单取）。 */
public record SkillFileView(
        String id,
        String path,
        boolean binary,
        long size,
        String contentType,
        Instant createdAt,
        Instant updatedAt) {
}
