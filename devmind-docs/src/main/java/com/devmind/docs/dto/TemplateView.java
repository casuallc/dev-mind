package com.devmind.docs.dto;

/**
 * 文档模板（FR-07）：kind 对应的预置内容，新建时一键选用。
 */
public record TemplateView(String kind, String name, String content) {
}
