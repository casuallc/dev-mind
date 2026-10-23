package com.devmind.knowledge.dto;

import java.util.List;

/**
 * @param inferred false = 目录未命中规则，使用批次默认分类
 */
public record ClassifyPreviewItem(String relativePath, String docType, boolean inferred, List<String> tags) {
}
