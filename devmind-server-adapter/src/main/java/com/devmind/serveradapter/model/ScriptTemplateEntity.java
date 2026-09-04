package com.devmind.serveradapter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * script_templates 表（CAP-07 FR-05 命令模板白名单）：项目预定义的远程执行模板，占位符参数化。
 * 远程执行只允许引用本表中的模板 code，杜绝任意命令拼接。
 */
@Entity
@Table(name = "script_templates")
public class ScriptTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** 模板编码（项目内唯一，执行时引用） */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** 模板正文（shell 脚本，含 ${param} 占位符） */
    @Lob
    @Column(name = "template_text", length = 16_777_216)
    private String templateText;

    /** JSON：参数 schema [{name,required,label,default}] */
    @Lob
    @Column(name = "params_schema", length = 16_777_216)
    private String paramsSchema;

    /** 逗号分隔：允许使用的能力（build/deploy/release/test/logs/exec），空=不限 */
    @Column(length = 128)
    private String allowed;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTemplateText() { return templateText; }
    public void setTemplateText(String templateText) { this.templateText = templateText; }
    public String getParamsSchema() { return paramsSchema; }
    public void setParamsSchema(String paramsSchema) { this.paramsSchema = paramsSchema; }
    public String getAllowed() { return allowed; }
    public void setAllowed(String allowed) { this.allowed = allowed; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
