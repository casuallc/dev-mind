package com.devmind.test.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * test_runs 表（CAP-10）：一次测试执行。状态机 QUEUED → RUNNING → SUCCESS（无失败用例）| FAILED。
 * suiteIdsJson 为选中的套件 id 数组；summaryJson = {"total":n,"passed":n,"failed":n,"skipped":n}；
 * reportDocId 为 CAP-03 的 report 文档 id（FR-04）。
 */
@Entity
@Table(name = "test_runs", indexes = {
        @Index(name = "idx_test_runs_project_created", columnList = "project_id, created_at"),
        @Index(name = "idx_test_runs_plan_run", columnList = "plan_run_id")
})
public class TestRunEntity {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    /** CAP-104：环境预检未通过 / 无可用节点等前置条件缺失，不产生假失败 */
    public static final String BLOCKED = "BLOCKED";

    public static final String SCOPE_SUITE = "SUITE";
    public static final String SCOPE_CASE = "CASE";
    public static final String SCOPE_SCRIPT = "SCRIPT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CAP-69 起可空：脚本套件（kind=script）的运行不属任何项目 */
    @Column(name = "project_id", length = 32)
    private String projectId;

    /** P0-6 关联约定：需求 id（可空 = 项目级回归） */
    @Column(name = "work_item_id", length = 32)
    private String workItemId;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "suite_ids_json", length = 16_777_216)
    private String suiteIdsJson;

    @Column(name = "deployment_id")
    private Long deploymentId;

    /** health command 用例的执行节点（CAP-36：agent_nodes 表 id；取代 servers 表 server_id） */
    @Column(name = "agent_node_id", length = 64)
    private String agentNodeId;

    /** 目标环境（P1-1 environments 表 id；环境提供默认节点与 baseUrl 变量） */
    @Column(name = "environment_id")
    private Long environmentId;

    /** API 测试目标 base URL（http 用例），可空（从环境变量推导） */
    @Column(length = 512)
    private String baseUrl;

    @Column(length = 16)
    private String status;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "summary_json", length = 16_777_216)
    private String summaryJson;

    @Column(name = "report_doc_id")
    private Long reportDocId;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error_summary", length = 16_777_216)
    private String errorSummary;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "logs_text", length = 16_777_216)
    private String logsText;

    /** user / deploy */
    @Column(name = "triggered_by", length = 16)
    private String triggeredBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    // ---------------- CAP-104 运行扩展 ----------------

    /** M2 方案父运行 id（M1 为空） */
    @Column(name = "plan_run_id")
    private Long planRunId;

    /** M2 方案项 id（一个方案项可因重试产生多个 attempt 子运行） */
    @Column(name = "plan_item_id")
    private Long planItemId;

    /** M2 第几次尝试（方案项重试；普通运行 = 1） */
    @Column(name = "attempt")
    private Integer attempt;

    /**
     * 运行期用例快照（方案运行固化后再编辑用例不影响本次运行）。
     * 结构见 {@link com.devmind.test.dto.RunExecutionSnapshot}；普通运行保持空走实时数据。
     */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "execution_snapshot_json", length = 16_777_216)
    private String executionSnapshotJson;

    /** 关联需求 id（M2 使用；工作单元仍用 workItemId） */
    @Column(name = "requirement_id", length = 32)
    private String requirementId;

    /** 分布式分片键（M3；M1 为空） */
    @Column(name = "shard_key", length = 64)
    private String shardKey;

    /** PASS / FAIL / PARTIAL / BLOCKED（父运行/方案运行使用，M1 可为空） */
    @Column(length = 16)
    private String conclusion;

    /** SUITE / CASE / SCRIPT（缺省按套件运行） */
    @Column(name = "run_scope", length = 16)
    private String runScope;

    /** CASE 作用域的单用例 id（M2 方案运行不使用） */
    @Column(name = "case_id")
    private Long caseId;

    /** 触发参数注入（CASE 运行；不落 secret 原值，仅运行期使用） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "parameters_json", length = 16_777_216)
    private String parametersJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getSuiteIdsJson() { return suiteIdsJson; }
    public void setSuiteIdsJson(String suiteIdsJson) { this.suiteIdsJson = suiteIdsJson; }
    public Long getDeploymentId() { return deploymentId; }
    public void setDeploymentId(Long deploymentId) { this.deploymentId = deploymentId; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public Long getReportDocId() { return reportDocId; }
    public void setReportDocId(Long reportDocId) { this.reportDocId = reportDocId; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public String getLogsText() { return logsText; }
    public void setLogsText(String logsText) { this.logsText = logsText; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getPlanRunId() { return planRunId; }
    public void setPlanRunId(Long planRunId) { this.planRunId = planRunId; }
    public Long getPlanItemId() { return planItemId; }
    public void setPlanItemId(Long planItemId) { this.planItemId = planItemId; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public String getExecutionSnapshotJson() { return executionSnapshotJson; }
    public void setExecutionSnapshotJson(String executionSnapshotJson) { this.executionSnapshotJson = executionSnapshotJson; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getShardKey() { return shardKey; }
    public void setShardKey(String shardKey) { this.shardKey = shardKey; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public String getRunScope() { return runScope; }
    public void setRunScope(String runScope) { this.runScope = runScope; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }
}
