package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 知识库容器（CAP-44 FR-01）：条目的一等归属。CAP-94 起知识库去项目化：库是完全独立
 * 的平台资产，scope/projectId 停写（列保留不删防 rollback，存量行原样）；消费方式统一为
 * 显式绑库后的检索命中注入（CAP-92 下线 FULL 全量注入）。
 */
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBaseEntity {

    /** 历史常量（CAP-94 停写）：仅存量行与 KnowledgeBaseMigration 迁移 SQL 可读 */
    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_PROJECT = "project";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ARCHIVED = "archived";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CAP-95: null means legacy/unassigned, never public. */
    private Long policyId;
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long policyId) { this.policyId = policyId; }

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /** 历史列：global | project（CAP-94 起停写，库不再分层） */
    @Column(nullable = false, length = 16)
    private String scope = SCOPE_GLOBAL;

    /** 历史列：scope=project 时所属项目（CAP-94 起停写） */
    @Column(length = 64)
    private String projectId;

    /**
     * CAP-92：FULL 全量注入已下线，该列仅为兼容存量表结构与回滚保留，取值恒为 RAG。
     * 保留映射是为了不破坏 nullable=false 的旧库插入（ddl-auto 不删列）。
     */
    @Column(nullable = false, length = 8)
    private String injectMode = "RAG";

    /**
     * CAP-48 FR-04：库级覆盖的 embedding 端点（空 = 平台默认端点）。
     * 取代 CAP-44 的 embedding_model（那是个从未被任何链路读取的死字段，本列已停写）。
     */
    @Column(name = "model_endpoint_id")
    private Long modelEndpointId;

    /** active | archived（archived 不参与检索） */
    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getInjectMode() { return injectMode; }
    public void setInjectMode(String injectMode) { this.injectMode = injectMode; }
    public Long getModelEndpointId() { return modelEndpointId; }
    public void setModelEndpointId(Long modelEndpointId) { this.modelEndpointId = modelEndpointId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
