package com.devmind.knowledge.dto;

import java.util.List;

/**
 * 注入内容预览（FR-04）。
 *
 * @param content    组装后的 CLAUDE.md 全文
 * @param entriesUsed 参与注入的条目（含标签命中情况）
 */
public record PreviewResult(String content, List<EntryView> entriesUsed) {
}
