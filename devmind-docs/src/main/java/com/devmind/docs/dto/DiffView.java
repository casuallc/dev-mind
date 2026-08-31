package com.devmind.docs.dto;

import java.util.List;

/**
 * 版本差异（FR-02）：简化 unified diff 行 + 增删计数。
 */
public record DiffView(boolean hasChanges, List<String> lines, int additions, int deletions) {
}
