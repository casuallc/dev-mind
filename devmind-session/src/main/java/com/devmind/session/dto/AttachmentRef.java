package com.devmind.session.dto;

/**
 * CAP-68 会话输入的附件引用（attachmentId 指向 CAP-32 附件模块，二进制本体不落 session 表）：
 * images=图片（走 images 帧 image block 直读）；files=任意文件（runner 落盘工作区由 agent Read）。
 */
public record AttachmentRef(String attachmentId, String name, String contentType) {
}
