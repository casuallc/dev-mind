package com.devmind.knowledge.dto;

/**
 * CAP-48 FR-08 重建索引结果。
 *
 * @param queued 本次置为 pending 并投递索引事件（异步）的条目数
 */
public record ReindexResult(long queued) {
}
