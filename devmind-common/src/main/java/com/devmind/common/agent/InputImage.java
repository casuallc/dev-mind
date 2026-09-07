package com.devmind.common.agent;

import java.util.Objects;

/**
 * CAP-32 注入 input 的图片附件载体：base64Data 为下发 claude 的素材（stream-json
 * image content block）；attachmentId/name 仅用于事件流回显（user 事件
 * payload.attachments 只放引用，不落 base64，防 WS snapshot 与 CLOB 膨胀）。
 */
public record InputImage(String attachmentId, String name, String mediaType, String base64Data) {

    public InputImage {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(base64Data, "base64Data");
    }
}
