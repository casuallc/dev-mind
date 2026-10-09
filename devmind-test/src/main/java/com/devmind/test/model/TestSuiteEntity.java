package com.devmind.test.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * test_suites 表（CAP-10）：测试套件。kind = api（由 OpenAPI 生成的 API 套件）| smoke（冒烟套件）
 * | script（CAP-69 独立脚本套件，不绑项目：自带 git 源与命令，整包下发 runner 跑，JUnit 回收解析）。
 * source = openapi（生成）/ manual（手工）。docId 为 CAP-03 的 api-suite 文档（FR-03）。
 */
@Entity
@Table(name = "test_suites")
public class TestSuiteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CAP-69 起可空：script 套件独立于项目；api/smoke 套件仍非空 */
    @Column(name = "project_id", length = 32)
    private String projectId;

    @Column(nullable = false, length = 128)
    private String name;

    /** api / smoke / script */
    @Column(length = 16)
    private String kind;

    /** openapi / manual */
    @Column(length = 32)
    private String source;

    /** CAP-03 的 api-suite 文档 id（FR-03），可空 */
    @Column(name = "doc_id")
    private Long docId;

    // ---------------- CAP-69 script 套件字段（其余 kind 恒为 NULL） ----------------

    /** git 仓库地址（runner 侧 clone；v1 token 恒空，靠节点 git 凭据） */
    @Column(name = "repo_url", length = 512)
    private String repoUrl;

    @Column(length = 128)
    private String branch;

    /** 仓库内子目录（如 e2e），可空 = 仓库根 */
    @Column(name = "work_subdir", length = 256)
    private String workSubdir;

    /** 执行命令模板（多行脚本串；服务端包装 JUnit 回收尾段后下发） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String command;

    /** JUnit XML 产出路径（相对 workSubdir） */
    @Column(name = "junit_path", length = 256)
    private String junitPath;

    /** [{key,value,secret}]；secret=true 的值视图层掩码 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "env_json", length = 16_777_216)
    private String envJson;

    /** 套件默认执行节点（agent_nodes 表 id；可空 = 触发时路由链兜底） */
    @Column(name = "agent_node_id", length = 64)
    private String agentNodeId;

    /** 单步超时（秒）；null = 7200（Playwright 整包远超全局 30min 默认） */
    @Column(name = "timeout_sec")
    private Integer timeoutSec;

    /** runner 工作区复用键（默认套件 id；有跨套件状态依赖的套件族配同一个 key） */
    @Column(name = "workspace_key", length = 128)
    private String workspaceKey;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getWorkSubdir() { return workSubdir; }
    public void setWorkSubdir(String workSubdir) { this.workSubdir = workSubdir; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getJunitPath() { return junitPath; }
    public void setJunitPath(String junitPath) { this.junitPath = junitPath; }
    public String getEnvJson() { return envJson; }
    public void setEnvJson(String envJson) { this.envJson = envJson; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public Integer getTimeoutSec() { return timeoutSec; }
    public void setTimeoutSec(Integer timeoutSec) { this.timeoutSec = timeoutSec; }
    public String getWorkspaceKey() { return workspaceKey; }
    public void setWorkspaceKey(String workspaceKey) { this.workspaceKey = workspaceKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
