package com.devmind.common.agent;

import java.util.Objects;

/**
 * CAP-68 注入 input 的文件附件载体：base64Data 为随 input 帧下发 runner 的素材
 * （runner 落盘会话工作区 {@code .devmind/incoming/<attachmentId>-<文件名>}，并在发给
 * claude 的消息尾部追加路径提示，由 agent 用 Read 自取）；attachmentId/name 同时用于
 * 事件流回显（user 事件 payload.attachments 只放引用，不落 base64）。
 */
public record InputFile(String attachmentId, String name, String mediaType, String base64Data) {

    public InputFile {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(base64Data, "base64Data");
    }
}
