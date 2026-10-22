package com.devmind.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * session_outputs 表（CAP-37 FR-01）：会话产出的结构化文件内容
 * （runner 退出前回传的 .devmind/output/*），同 sessionId+fileName 只留最新一份。
 */
@Entity
@Table(name = "session_outputs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_session_outputs_file", columnNames = {"session_id", "file_name"})
})
public class SessionOutputEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    /** 纯文件名（不含路径），如 analysis.md / design.md / wi-plan.json */
    @Column(name = "file_name", length = 64, nullable = false)
    private String fileName;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String content;

    /** CAP-83 附件产出（xlsx 等二进制）：attachments.id 引用；null = 文本产出（正文在
     *  content）。与 content 恰一非空；同名互覆时清另一列并删旧附件。 */
    @Column(name = "attachment_id", length = 32)
    private String attachmentId;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }
    /** CAP-83：是否附件产出（正文在 attachments 表，content 恒 null）。 */
    public boolean isAttachment() { return attachmentId != null && !attachmentId.isBlank(); }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
