package com.devmind.attachment.dto;

/**
 * CAP-68 附件元数据更新请求。三字段同语义：null=不变，空白串=清除，否则覆盖。
 * expiresAt 用全局时间格式「yyyy-MM-dd HH:mm:ss」。
 */
public record MetaUpdateRequest(String description, String tags, String expiresAt) {
}
