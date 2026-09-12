package com.devmind.common.attachment;

import java.util.Optional;

/**
 * CAP-32 附件内容解析 SPI（chat 等运行时 → attachment 模块：把 attachmentId 解析为
 * 下发 claude 的图片素材）。实现方 devmind-attachment 注册 Bean；消费方以
 * {@code ObjectProvider<AttachmentContentResolver>} 探测注入，未装配时带附件的输入
 * 应报错提示（不静默丢图），纯文本输入不受影响。
 */
public interface AttachmentContentResolver {

    /**
     * 按 attachmentId 读字节与 contentType；附件不存在或非图片类型返回 empty。
     * 调用方负责业务权限判定（如 chat 已鉴权会话归属）。
     */
    Optional<ResolvedAttachment> resolve(String attachmentId);

    /**
     * CAP-40：不限 mime 类型读字节（需求附件投送：pdf/text 等一并给 agent）。
     * default 回落 {@link #resolve}（仅图片）保兼容；attachment 模块覆写为全类型。
     */
    default Optional<ResolvedAttachment> resolveAny(String attachmentId) {
        return resolve(attachmentId);
    }

    /** @param attachmentId 附件 id；@param contentType 原始 mime；@param bytes 原始字节 */
    record ResolvedAttachment(String attachmentId, String contentType, byte[] bytes) {}
}
