package com.devmind.knowledge.triage;

/**
 * CAP-55 FR-04 提案入库事件：新提案落库后自动分诊的触发点。
 *
 * <p>与 {@code EntryContentChangedEvent} 同款——只带 id，不携带实体：
 * 监听方是 {@code AFTER_COMMIT} 的异步线程，拿实体引用是一份可能已过期的快照，
 * 读键值更省事也更安全。事件里<b>不含</b>分诊结果（那是监听方的产出）。</p>
 *
 * @param proposalId 新提案 id
 */
public record ProposalCreatedEvent(long proposalId) {
}
