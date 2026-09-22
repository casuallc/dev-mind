package com.devmind.decisionlab.finetune.model;

import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
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
 * CAP-56 FR-05 微调任务（{@code decision_finetunes}）：一次「在某份基座上、用某个训练集的一个切分、
 * 在某个 GPU 节点上跑 RLCD」。
 *
 * <p><b>切分判据进库，不进命令行</b>（{@link #valItemIdsJson}）：训练/验证怎么分是一次实验的
 * 一部分，而它是"这次实验"的事实，不是"重跑一次能再算出来"的东西。若只留一个 seed 让脚本现算，
 * 那么"这份 val 指标是在哪几条样本上算的"就只剩一个数字的记忆——框架升级、数据顺序变化都可能让
 * 同一个 seed 切出不同的集合，而报告上的数字看起来毫无变化。所以服务端切一次、记下来、照发。</p>
 *
 * <p><b>回评用另一个评测集</b>（{@link #evalDatasetId}）：微调集的一部分被拿去训练了，
 * 拿它回评等于用练习题当考卷——FR-05 要的"结束自动回评"若在这份集上做，指标只会好看，
 * 而看不出泛化。所以训练集与回评集是两列，且触发时拒绝二者相同。</p>
 *
 * <p><b>权重留节点，库里只留指纹与指标</b>（同 {@code DecisionCheckpointEntity}）：
 * {@link #fingerprintJson} 是节点算出来的 {@code {path, bytes, sha256}}，服务端拿它登记一条产物
 * ——那是这次训练<b>唯一</b>能被指认的产出凭据（输出目录会被下一轮训练就地覆盖）。</p>
 *
 * <p><b>跑完之后还有两件事要做</b>（登记产物 → 触发回评），它们各自可能失败
 * （名字撞了、节点掉了、脚本没打指纹）。{@link #checkpointId}/{@link #evalId} 为空 +
 * {@link #postError} 有话说，就是"训练本身成功了但收尾没做完"的显式状态：这三者都留着，
 * 才不至于让人对着一条 SUCCESS 找不到产物。</p>
 */
@Entity
@Table(name = "decision_finetunes")
public class DecisionFinetuneEntity {

    /** 状态词表与评测共用一份（同一个执行底座、同一张页面表格）：别名而不是各自写字面量 */
    public static final String QUEUED = DecisionEvaluationEntity.QUEUED;
    public static final String RUNNING = DecisionEvaluationEntity.RUNNING;
    public static final String SUCCESS = DecisionEvaluationEntity.SUCCESS;
    public static final String FAILED = DecisionEvaluationEntity.FAILED;

    /** 报告状态的四种取值与评测同义（同一个脚本契约、同一套判据） */
    public static final String REPORT_OK = DecisionEvaluationEntity.REPORT_OK;
    public static final String REPORT_MISSING = DecisionEvaluationEntity.REPORT_MISSING;
    public static final String REPORT_MALFORMED = DecisionEvaluationEntity.REPORT_MALFORMED;
    public static final String REPORT_INCOMPLETE = DecisionEvaluationEntity.REPORT_INCOMPLETE;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ---------------- 训练集（快照） ----------------

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "dataset_name", length = 128)
    private String datasetName;

    @Column(name = "dataset_version")
    private Integer datasetVersion;

    @Column(name = "question_set_version", length = 64)
    private String questionSetVersion;

    /** 训练集总条数 */
    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "train_count")
    private Integer trainCount;

    @Column(name = "val_count")
    private Integer valCount;

    /** 切分随机种子（与训练种子分开：实验要能分别固定"哪些样本做验证"与"训练怎么抽"） */
    @Column(name = "split_seed")
    private Long splitSeed;

    /** 训练集占的比例（切分明细以 {@link #valItemIdsJson} 为准，这里只留判据原值） */
    @Column(name = "train_ratio")
    private Double trainRatio;

    /** 验证集条目 id（JSON 数组）：这次实验"考的是哪几道题"的唯一凭据 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "val_item_ids_json", length = 16_777_216)
    private String valItemIdsJson;

    // ---------------- 回评集（快照） ----------------

    @Column(name = "eval_dataset_id", nullable = false)
    private Long evalDatasetId;

    @Column(name = "eval_dataset_name", length = 128)
    private String evalDatasetName;

    @Column(name = "eval_dataset_version")
    private Integer evalDatasetVersion;

    // ---------------- 基座与产出位置 ----------------

    @Column(name = "base_checkpoint_id", nullable = false)
    private Long baseCheckpointId;

    @Column(name = "base_checkpoint_name", length = 128)
    private String baseCheckpointName;

    @Column(name = "base_serve_slot", length = 64)
    private String baseServeSlot;

    @Column(name = "base_checkpoint_path", length = 512)
    private String baseCheckpointPath;

    /**
     * 产出将服务的槽位（= 基座槽位）。
     *
     * <p>本 CAP 只换来源不改名字：槽位决定题面与原语，换槽位就是换了一个模型该答的题——
     * 那不是微调，是另一件事。所以这里跟着基座走，不让人选。</p>
     */
    @Column(name = "serve_slot", length = 64)
    private String serveSlot;

    /** 节点上的产出目录（权重留在这里，服务端只收回指纹） */
    @Column(name = "output_path", length = 512)
    private String outputPath;

    // ---------------- 超参（快照） ----------------

    @Column
    private Integer epochs;

    @Column(name = "learning_rate")
    private Double learningRate;

    @Column(name = "batch_size")
    private Integer batchSize;

    @Column(name = "train_seed")
    private Long trainSeed;

    /** 多卡启动前缀（如 {@code torchrun --nproc_per_node=2}；空 = 单进程） */
    @Column(length = 256)
    private String launcher;

    // ---------------- 执行 ----------------

    @Column(name = "node_id", length = 32)
    private String nodeId;

    /**
     * 本次训练用的解释器路径（节点上的 python）。
     *
     * <p>存下来是为了<b>让自动回评用同一个解释器</b>：训练时若在页面上覆盖过
     * {@code devmind.decision-lab.python-path}（GPU 节点上通常是某个 venv 的全路径），
     * 回评却回头用平台默认值，就会在同一个节点上"训练能跑、回评跑不起来"——
     * 这种失败看起来像回评功能坏了，其实是路径丢了。</p>
     */
    @Column(name = "python_path", length = 512)
    private String pythonPath;

    @Column(name = "timeout_seconds")
    private Long timeoutSeconds;

    @Column(length = 16)
    private String status;

    /** 渲染后的单行命令（诊断"到底在节点上跑了什么"的第一手材料） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "command_text", length = 16_777_216)
    private String commandText;

    @Column(name = "report_status", length = 16)
    private String reportStatus;

    /** 报告头条（列表页用，与评测同结构） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "headline_json", length = 16_777_216)
    private String headlineJson;

    /** 微调自身的报告（在验证切分上的指标 + 训练过程 + 温度校准） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "metrics_json", length = 16_777_216)
    private String metricsJson;

    /** 产物指纹 {@code {"path":…,"bytes":…,"sha256":…}}（脚本结束前打印，服务端登记产物用） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "fingerprint_json", length = 16_777_216)
    private String fingerprintJson;

    /** 自动登记出来的产物行（空 = 没登记成，原因看 {@link #postError}） */
    @Column(name = "checkpoint_id")
    private Long checkpointId;

    @Column(name = "checkpoint_name", length = 128)
    private String checkpointName;

    /** 自动触发的回评（空 = 没触发成，原因看 {@link #postError}） */
    @Column(name = "eval_id")
    private Long evalId;

    /**
     * 跑完之后那两件事的失败原因（人读）。
     *
     * <p>不把它们并进 {@code error_summary}：训练的成败与收尾的成败是两回事，
     * 混在一起会让人以为"训练挂了"。也不因为收尾失败就把状态改成 FAILED——训练确实跑完了、
     * 权重确实在节点上，那是一份真实的产出，删掉这个事实只会让人重跑一次整夜的训练。</p>
     */
    @Column(name = "post_error", length = 2000)
    private String postError;

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
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public Integer getTrainCount() { return trainCount; }
    public void setTrainCount(Integer trainCount) { this.trainCount = trainCount; }
    public Integer getValCount() { return valCount; }
    public void setValCount(Integer valCount) { this.valCount = valCount; }
    public Long getSplitSeed() { return splitSeed; }
    public void setSplitSeed(Long splitSeed) { this.splitSeed = splitSeed; }
    public Double getTrainRatio() { return trainRatio; }
    public void setTrainRatio(Double trainRatio) { this.trainRatio = trainRatio; }
    public String getValItemIdsJson() { return valItemIdsJson; }
    public void setValItemIdsJson(String valItemIdsJson) { this.valItemIdsJson = valItemIdsJson; }
    public Long getEvalDatasetId() { return evalDatasetId; }
    public void setEvalDatasetId(Long evalDatasetId) { this.evalDatasetId = evalDatasetId; }
    public String getEvalDatasetName() { return evalDatasetName; }
    public void setEvalDatasetName(String evalDatasetName) { this.evalDatasetName = evalDatasetName; }
    public Integer getEvalDatasetVersion() { return evalDatasetVersion; }
    public void setEvalDatasetVersion(Integer evalDatasetVersion) { this.evalDatasetVersion = evalDatasetVersion; }
    public Long getBaseCheckpointId() { return baseCheckpointId; }
    public void setBaseCheckpointId(Long baseCheckpointId) { this.baseCheckpointId = baseCheckpointId; }
    public String getBaseCheckpointName() { return baseCheckpointName; }
    public void setBaseCheckpointName(String baseCheckpointName) { this.baseCheckpointName = baseCheckpointName; }
    public String getBaseServeSlot() { return baseServeSlot; }
    public void setBaseServeSlot(String baseServeSlot) { this.baseServeSlot = baseServeSlot; }
    public String getBaseCheckpointPath() { return baseCheckpointPath; }
    public void setBaseCheckpointPath(String baseCheckpointPath) { this.baseCheckpointPath = baseCheckpointPath; }
    public String getServeSlot() { return serveSlot; }
    public void setServeSlot(String serveSlot) { this.serveSlot = serveSlot; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
    public Integer getEpochs() { return epochs; }
    public void setEpochs(Integer epochs) { this.epochs = epochs; }
    public Double getLearningRate() { return learningRate; }
    public void setLearningRate(Double learningRate) { this.learningRate = learningRate; }
    public Integer getBatchSize() { return batchSize; }
    public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }
    public Long getTrainSeed() { return trainSeed; }
    public void setTrainSeed(Long trainSeed) { this.trainSeed = trainSeed; }
    public String getLauncher() { return launcher; }
    public void setLauncher(String launcher) { this.launcher = launcher; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getPythonPath() { return pythonPath; }
    public void setPythonPath(String pythonPath) { this.pythonPath = pythonPath; }
    public Long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCommandText() { return commandText; }
    public void setCommandText(String commandText) { this.commandText = commandText; }
    public String getReportStatus() { return reportStatus; }
    public void setReportStatus(String reportStatus) { this.reportStatus = reportStatus; }
    public String getHeadlineJson() { return headlineJson; }
    public void setHeadlineJson(String headlineJson) { this.headlineJson = headlineJson; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public String getFingerprintJson() { return fingerprintJson; }
    public void setFingerprintJson(String fingerprintJson) { this.fingerprintJson = fingerprintJson; }
    public Long getCheckpointId() { return checkpointId; }
    public void setCheckpointId(Long checkpointId) { this.checkpointId = checkpointId; }
    public String getCheckpointName() { return checkpointName; }
    public void setCheckpointName(String checkpointName) { this.checkpointName = checkpointName; }
    public Long getEvalId() { return evalId; }
    public void setEvalId(Long evalId) { this.evalId = evalId; }
    public String getPostError() { return postError; }
    public void setPostError(String postError) { this.postError = postError; }
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
