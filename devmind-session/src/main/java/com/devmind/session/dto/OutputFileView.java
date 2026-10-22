package com.devmind.session.dto;

import java.time.Instant;

/** CAP-39：会话产出文件列表项（GET /sessions/{id}/outputs）。
 *  CAP-83：attachmentId 非空 = 附件产出（xlsx 等二进制，下载/预览走 attachments raw 端点，
 *  contentType 为该附件 mime）；两者 null = 文本产出（正文走内容端点）。 */
public record OutputFileView(String fileName, long sizeBytes, Instant updatedAt,
                             String attachmentId, String contentType) {
}
