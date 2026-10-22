package com.devmind.common.attachment;

import java.io.InputStream;

/**
 * CAP-83 附件存储 SPI（common 定义，devmind-attachment 实现，消费方 ObjectProvider 探测）。
 * 供无登录态/跨模块链路把字节流存为公共附件（attachments 表 + 磁盘），首个消费方 =
 * devmind-session 的二进制会话产出编排（runner 回传的 xlsx 等进附件管理）。
 *
 * <p>与 {@link AttachmentContentResolver}（读）对称的写路径；uploadedBy/scope 由调用方
 * 显式传入（系统链路无 SecurityContext，不走 AttachmentService 的当前用户判定）。</p>
 */
public interface AttachmentStore {

    /** 可见性：仅上传者+ADMIN（与 AttachmentEntity.SCOPE_PRIVATE 对齐，common 不依赖 attachment 模块） */
    String SCOPE_PRIVATE = "PRIVATE";
    /** 可见性：全体登录用户可读 */
    String SCOPE_SHARED = "SHARED";

    /**
     * 字节流落盘 + 元数据登记，返回 attachmentId。
     *
     * @param in          内容流（调用方不关闭，实现方负责关闭）
     * @param size        字节数（实现方据此卡上限，超限抛 BAD_REQUEST）
     * @param originalName 原始文件名（纯文件名）
     * @param contentType mime，null/blank 兜底 application/octet-stream
     * @param scope       PRIVATE / SHARED（见 AttachmentEntity.SCOPE_*）
     * @param uploadedBy  归属用户名（附件管理页可见性判定依据）
     * @param tags        逗号分隔标签（可 null）
     * @param description 描述（可 null）
     */
    String store(InputStream in, long size, String originalName, String contentType,
                 String scope, String uploadedBy, String tags, String description);

    /**
     * 系统内部删除（元数据 + 磁盘文件），bypass 用户校验——仅限系统编排路径调用
     * （如 CAP-83 产出附件同名覆盖删旧）；不存在静默忽略。
     */
    void delete(String attachmentId);
}
