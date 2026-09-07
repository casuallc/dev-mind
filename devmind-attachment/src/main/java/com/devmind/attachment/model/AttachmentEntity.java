package com.devmind.attachment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * attachments 表：CAP-32 公共附件元数据。
 * 主键即对外唯一字符串 id（attachmentId，32 位 hex）——URL 与跨能力引用都用它，
 * 不暴露内部自增主键与磁盘路径。
 */
@Entity
@Table(name = "attachments")
public class AttachmentEntity {

    /** 对外唯一字符串 id（SecureRandom 16 字节 hex） */
    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    /** PRIVATE=仅上传者+ADMIN / SHARED=全体登录用户可读 */
    @Column(length = 16)
    @ColumnDefault("'PRIVATE'")
    private String scope;

    /** 相对 rootDir 的存储路径（yyyy/MM/<id>.<ext>） */
    @Column(name = "storage_path", length = 512)
    private String storagePath;

    @Column(name = "uploaded_by", length = 64)
    private String uploadedBy;

    @Column(name = "created_at")
    private Instant createdAt;

    public static final String SCOPE_PRIVATE = "PRIVATE";
    public static final String SCOPE_SHARED = "SHARED";

    /** 图片类附件（图床用法，内联渲染）；非图片仅下载。 */
    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
