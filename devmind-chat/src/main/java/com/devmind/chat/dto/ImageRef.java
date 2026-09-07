package com.devmind.chat.dto;

/**
 * CAP-32 问答输入的图片附件引用（attachmentId 指向 CAP-32 附件模块，二进制本体不落 chat 表）。
 */
public record ImageRef(String attachmentId, String name, String contentType) {
}
