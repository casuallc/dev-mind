package com.devmind.decisionlab.eval.model;

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
 * CAP-56 FR-03 评测运行（{@code decision_evaluations}）：一次「某份 checkpoint × 某个冻结集
 * × 某个节点」的评测，产出指标、基线、逐题结果与（可选的）温度校准。
 *
 * <p><b>所有身份信息都是快照，不是外键</b>：dataset/checkpoint 的名字、版本、槽位在触发那一刻
 * 抄进本行。评测集会被"修订为新版本"、checkpoint 目录会被下一轮训练覆盖、登记行可能被删——
 * 报告要能永远说清"当时测的是哪一份"，靠 join 现查的话，改一次名字，历史报告的标题就跟着变了。</p>
 *
 * <p><b>报告不拆列</b>（{@link #reportJson} 一整份）：指标、基线、逐题、对照、校准是同一次执行
 * 一次性产出的一个整体，拆成五列只会让人能拼出一个组件各来自不同执行时间的"报告"。
 * 列表页要显示的头条数字另有 {@link #headlineJson}（小、稳定、专门给列表用），
 * 避免为了渲染一页 20 行去解析 20 份大报告。</p>
 *
 * <p><b>跑完 ≠ 有指标</b>：脚本可能因为崩溃/超时/忘了打 marker 而没产出报告，此时退出码可能是 0
 * 也可能是非 0。{@link #reportStatus} 把这个状态显式记下来（OK/MISSING/MALFORMED/INCOMPLETE）——
 * 「这次跑完了但没有指标」必须在页面上一眼看见，而不是显示成一份空报告让人以为模型全错。</p>
 */
@Entity
@Table(name = "decision_evaluations")
public class DecisionEvaluationEntity {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    /** 报告齐备（metrics + baselines 都在） */
    public static final String REPORT_OK = "OK";
    /** 脚本没打 DEVMIND_REPORT 行（漏了、崩在打印之前、或老脚本） */
    public static final String REPORT_MISSING = "MISSING";
    /** 打了但解不出来（载荷损坏 / 脚本与平台版本不一致） */
    public static final String REPORT_MALFORMED = "MALFORMED";
    /** 解出来了但缺必报项（无 metrics 或无 baselines）——FR-03 的基线是硬要求 */
    public static final String REPORT_INCOMPLETE = "INCOMPLETE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "dataset_name", length = 128)
    private String datasetName;

    /** 触发时那份评测集的修订号（评测集可"修订为新版本"，报告要认得出自己测的是哪一版） */
    @Column(name = "dataset_version")
    private Integer datasetVersion;

    /** 触发时那套题面版本（题面是代码常量，改一句文案就是换训练目标） */
    @Column(name = "question_set_version", length = 64)
    private String questionSetVersion;

    @Column(name = "checkpoint_id", nullable = false)
    private Long checkpointId;

    @Column(name = "checkpoint_name", length = 128)
    private String checkpointName;

    @Column(name = "serve_slot", length = 32)
    private String serveSlot;

    /** 被评测 checkpoint 的本地路径 / HF 仓库名（触发时的值，节点上按它加载） */
    @Column(name = "checkpoint_path", length = 512)
    private String checkpointPath;

    /** 可选：对照基线 checkpoint（FR-03 的逐题胜负明细；空 = 不比对） */
    @Column(name = "base_checkpoint_id")
    private Long baseCheckpointId;

    @Column(name = "base_checkpoint_name", length = 128)
    private String baseCheckpointName;

    @Column(name = "base_checkpoint_path", length = 512)
    private String baseCheckpointPath;

    @Column(name = "node_id", length = 32)
    private String nodeId;

    @Column(name = "timeout_seconds")
    private Long timeoutSeconds;

    @Column(length = 16)
    private String status;

    /** 渲染后的单行命令（诊断"到底在节点上跑了什么"的第一手材料） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "command_text", length = 16_777_216)
    private String commandText;

    /** 评测条目数（触发时快照：报告里的 items 若与它不同，说明有样本被跳过） */
    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "report_status", length = 16)
    private String reportStatus;

    /** 报告头条数字（{@code {"accuracy":…,"random":…,"majority":…,"items":…}}，列表页用） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "headline_json", length = 16_777_216)
    private String headlineJson;

    /** 完整报告（指标/基线/逐题/对照/校准） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "report_json", length = 16_777_216)
    private String reportJson;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "logs_text", length = 16_777_216)
    private String logsText;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "error_summary", length = 2000)
    private String errorSummary;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }
    public Integer getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(Integer datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getQuestionSetVersion() { return questionSetVersion; }
    public void setQuestionSetVersion(String questionSetVersion) { this.questionSetVersion = questionSetVersion; }
    public Long getCheckpointId() { return checkpointId; }
    public void setCheckpointId(Long checkpointId) { this.checkpointId = checkpointId; }
    public String getCheckpointName() { return checkpointName; }
    public void setCheckpointName(String checkpointName) { this.checkpointName = checkpointName; }
    public String getServeSlot() { return serveSlot; }
    public void setServeSlot(String serveSlot) { this.serveSlot = serveSlot; }
    public String getCheckpointPath() { return checkpointPath; }
    public void setCheckpointPath(String checkpointPath) { this.checkpointPath = checkpointPath; }
    public Long getBaseCheckpointId() { return baseCheckpointId; }
    public void setBaseCheckpointId(Long baseCheckpointId) { this.baseCheckpointId = baseCheckpointId; }
    public String getBaseCheckpointName() { return baseCheckpointName; }
    public void setBaseCheckpointName(String baseCheckpointName) { this.baseCheckpointName = baseCheckpointName; }
    public String getBaseCheckpointPath() { return baseCheckpointPath; }
    public void setBaseCheckpointPath(String baseCheckpointPath) { this.baseCheckpointPath = baseCheckpointPath; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public Long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCommandText() { return commandText; }
    public void setCommandText(String commandText) { this.commandText = commandText; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public String getReportStatus() { return reportStatus; }
    public void setReportStatus(String reportStatus) { this.reportStatus = reportStatus; }
    public String getHeadlineJson() { return headlineJson; }
    public void setHeadlineJson(String headlineJson) { this.headlineJson = headlineJson; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public String getLogsText() { return logsText; }
    public void setLogsText(String logsText) { this.logsText = logsText; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public boolean isTerminal() {
        return SUCCESS.equals(status) || FAILED.equals(status);
    }
}
