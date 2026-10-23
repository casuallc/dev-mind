package com.devmind.knowledge.dto;

import java.util.List;

/** CAP-84 FR-15 上传分类预览：按目录规则预估每个文件的类型与标签，不落库。 */
public record ClassifyPreviewRequest(List<String> relativePaths, String defaultDocType) {
}
