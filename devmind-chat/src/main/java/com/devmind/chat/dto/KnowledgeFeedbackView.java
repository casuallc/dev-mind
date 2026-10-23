package com.devmind.chat.dto;

import java.time.Instant;
import java.util.List;

/** CAP-84 FR-25 单条答案反馈视图（含反馈时引用的文档快照）。 */
public record KnowledgeFeedbackView(
        long id,
        String chatId,
        long turnSeq,
        boolean useful,
        String reason,
        String mode,
        List<Ref> refs,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public record Ref(Long entryId, Long kbId, Long chunkId, Integer citationNo, String entryName) {
    }
}
