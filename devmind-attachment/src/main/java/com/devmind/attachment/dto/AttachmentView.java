package com.devmind.attachment.dto;

import com.devmind.attachment.model.AttachmentEntity;

import java.time.Instant;

/**
 * CAP-32 附件视图。url 为原始访问地址（前端按需拼 ?access_token=）；
 * image 标记前端渲染方式（图片内联 / 非图片下载）。
 */
public record AttachmentView(String attachmentId, String originalName, String contentType,
                             Long sizeBytes, String scope, boolean image, String url,
                             String uploadedBy, Instant createdAt) {

    public static AttachmentView of(AttachmentEntity e) {
        return new AttachmentView(e.getId(), e.getOriginalName(), e.getContentType(), e.getSizeBytes(),
                e.getScope(), e.isImage(), "/api/attachments/" + e.getId() + "/raw",
                e.getUploadedBy(), e.getCreatedAt());
    }
}
