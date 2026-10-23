package com.devmind.chat.dto;

/**
 * CAP-84 FR-19 引用快照视图：即使知识条目后来被改/删，仍可回溯当时依据。
 *
 * @param citationNo 回答里的 [S#] 编号
 * @param score      融合分（RRF）
 */
public record KnowledgeReferenceView(Long id, String chatId, long turnSeq, int citationNo,
                                     Long entryId, Long kbId, Long chunkId, String entryName,
                                     String sourceUri, String snippet, double score) {
}
