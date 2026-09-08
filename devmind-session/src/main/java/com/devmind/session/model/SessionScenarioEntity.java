package com.devmind.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * CAP-33 FR-01 session_scenarios 表：场景 = 命名模板 + 预装配上下文包
 * （session_templates 的升级版，模板行启动期迁移入本表后旧表代码层停用）。
 * 占位符：{{task}}/{{project}}/{{branch}}/{{requirement}}（纯字符串替换）。
 */
@Entity
@Table(name = "session_scenarios")
public class SessionScenarioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, unique = true, nullable = false)
    private String code;

    @Column(length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /** prompt 骨架（占位符渲染产物 = taskSpec，既作 launch prompt 也进 CLAUDE.md「当前任务」节） */
    @Lob
    @Column(name = "prompt_skeleton", length = 16_777_216)
    private String promptSkeleton;

    /** ①层显式绑定的 skill id 列表（JSON 数组串，全库 xxxJson 惯例手工序列化） */
    @Lob
    @Column(name = "skill_ids_json", length = 16_777_216)
    private String skillIdsJson;

    /** ①层显式绑定的 doc id 列表（JSON 数组串） */
    @Lob
    @Column(name = "doc_ids_json", length = 16_777_216)
    private String docIdsJson;

    /** ①层显式绑定的知识 tags（CSV，与 knowledge_entries.tags 同格式） */
    @Column(name = "knowledge_tags", length = 500)
    private String knowledgeTags;

    /** 业务背景/口径约定等自由文本 → CLAUDE.md「场景背景」节 */
    @Lob
    @Column(name = "extra_context_md", length = 16_777_216)
    private String extraContextMd;

    /** 场景预设（优先级：请求显式 > 场景 > 项目/平台默认） */
    @Column(length = 64)
    private String model;

    @Column(name = "permission_mode", length = 32)
    private String permissionMode;

    /** 场景预设执行节点（插在显式指定与项目默认之间，仍须过标签门控） */
    @Column(name = "agent_node_id", length = 64)
    private String agentNodeId;

    /** GLOBAL | PROJECT（PROJECT 需 projectId；chat 挂 PROJECT 场景 = 以该项目身份问答） */
    @Column(length = 16)
    private String scope;

    @Column(name = "project_id", length = 64)
    private String projectId;

    /** 列表/下拉只出 enabled；按 code 解析不查 enabled（兼容旧 templateCode 语义） */
    private boolean enabled;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPromptSkeleton() { return promptSkeleton; }
    public void setPromptSkeleton(String promptSkeleton) { this.promptSkeleton = promptSkeleton; }
    public String getSkillIdsJson() { return skillIdsJson; }
    public void setSkillIdsJson(String skillIdsJson) { this.skillIdsJson = skillIdsJson; }
    public String getDocIdsJson() { return docIdsJson; }
    public void setDocIdsJson(String docIdsJson) { this.docIdsJson = docIdsJson; }
    public String getKnowledgeTags() { return knowledgeTags; }
    public void setKnowledgeTags(String knowledgeTags) { this.knowledgeTags = knowledgeTags; }
    public String getExtraContextMd() { return extraContextMd; }
    public void setExtraContextMd(String extraContextMd) { this.extraContextMd = extraContextMd; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
