package com.devmind.decisionlab.finetune;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.decision.DecisionLabBundleProvider;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.checkpoint.CheckpointService;
import com.devmind.decisionlab.checkpoint.dto.CheckpointRequest;
import com.devmind.decisionlab.checkpoint.dto.CheckpointView;
import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import com.devmind.decisionlab.config.DecisionLabProperties;
import com.devmind.decisionlab.dataset.DatasetService;
import com.devmind.decisionlab.dataset.model.DecisionDatasetEntity;
import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import com.devmind.decisionlab.dataset.repo.DecisionDatasetItemRepository;
import com.devmind.decisionlab.eval.EvalReport;
import com.devmind.decisionlab.eval.EvalService;
import com.devmind.decisionlab.eval.dto.EvalTriggerRequest;
import com.devmind.decisionlab.eval.dto.EvalView;
import com.devmind.decisionlab.finetune.dto.FinetuneDetail;
import com.devmind.decisionlab.finetune.dto.FinetuneTriggerRequest;
import com.devmind.decisionlab.finetune.dto.FinetuneView;
import com.devmind.decisionlab.finetune.model.DecisionFinetuneEntity;
import com.devmind.decisionlab.finetune.repo.DecisionFinetuneRepository;
import com.devmind.decisionlab.lab.LabConcurrency;
import com.devmind.decisionlab.lab.LabMarkers;
import com.devmind.decisionlab.lab.LabScripts;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.execution.ws.ExecutionLogHub;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-56 FR-05 微调编排：触发（切分判据 → 训练集/回评集/基座/超参全部 fail-fast）→ 虚拟线程
 * 异步执行（runner exec 帧 + 执行包）→ 收报告与指纹落库 → <b>登记产物 + 触发回评</b>。
 *
 * <p><b>与评测同形，但收尾多两步</b>：训练本身跑完只是过程，这个 CAP 真正要的是"训出一份
 * 能被产品用上的权重"。所以 {@link #postSteps} 在退出码为 0 时继续做两件事——
 * 用节点算出的指纹登记一条产物（FR-06），再拿它跑一次评测（FR-05 的自动回评）。
 * 这两步<b>各自可能失败</b>，而它们的失败不该把训练判成失败：权重确实在节点上、指标确实算出来了，
 * 那是一份真实的产出。失败原因进 {@code post_error}，页面上与 SUCCESS 并列显示。</p>
 *
 * <p><b>训练集与回评集必须分开</b>：训练集里的一部分样本被拿去训练了，在那上面回评等于用练习题
 * 当考卷。所以回评集单独指定，且触发时拒绝与训练集相同——这条规则不靠人自觉，
 * 因为"看起来更好"是它唯一的症状。</p>
 *
 * <p><b>不在 @Transactional 里</b>（异步触发方法红线）：{@code save()} 自身事务即时提交，
 * 否则异步线程在另一条连接上看不到刚写的行，任务会永远停在 QUEUED。</p>
 */
@Service
public class FinetuneService {

    private static final Logger log = LoggerFactory.getLogger(FinetuneService.class);

    /**
     * 单次训练的最小允许超时（秒）。
     *
     * <p>比评测（60 秒）宽得多：一次 RLCD 训练连加载权重都不止一分钟，配一个 60 秒的超时
     * 只会得到一条"超时失败"——那不是人在表达"我要短训练"，而是把评测的默认值顺手抄了过来。</p>
     */
    private static final long MIN_TIMEOUT_SEC = 300;

    private static final String CAPABILITY = "decision-finetune";

    private static final int MAX_NAME_CHARS = 128;
    private static final int MAX_NOTE_CHARS = 512;

    private static final TypeReference<List<Long>> ID_LIST = new TypeReference<>() {
    };

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final DecisionFinetuneRepository repo;
    private final DecisionDatasetItemRepository itemRepo;
    private final DatasetService datasetService;
    private final CheckpointService checkpointService;
    private final EvalService evalService;
    private final DecisionLabProperties props;
    private final LabScripts scripts;
    private final LabConcurrency concurrency;
    private final AgentNodeRouter nodeRouter;
    private final AgentNodeStepRunner stepRunner;
    private final ExecutionLogHub hub;
    private final IdentityService identityService;
    private final ObjectMapper mapper;

    public FinetuneService(DecisionFinetuneRepository repo, DecisionDatasetItemRepository itemRepo,
                           DatasetService datasetService, CheckpointService checkpointService,
                           EvalService evalService, DecisionLabProperties props, LabScripts scripts,
                           LabConcurrency concurrency, AgentNodeRouter nodeRouter,
                           AgentNodeStepRunner stepRunner, ExecutionLogHub hub,
                           IdentityService identityService, ObjectMapper mapper) {
        this.repo = repo;
        this.itemRepo = itemRepo;
        this.datasetService = datasetService;
        this.checkpointService = checkpointService;
        this.evalService = evalService;
        this.props = props;
        this.scripts = scripts;
        this.concurrency = concurrency;
        this.nodeRouter = nodeRouter;
        this.stepRunner = stepRunner;
        this.hub = hub;
        this.identityService = identityService;
        this.mapper = mapper;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ---------------- 触发 ----------------

    public FinetuneView trigger(FinetuneTriggerRequest req) {
        if (req == null || req.datasetId() == null || req.evalDatasetId() == null
                || req.baseCheckpointId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "datasetId（训练集）、evalDatasetId（回评集）、baseCheckpointId（基座）均必填");
        }
        DecisionDatasetEntity dataset = datasetService.requireDataset(req.datasetId());
        requireFrozen(dataset, "训练集");
        DecisionDatasetEntity evalDataset = datasetService.requireDataset(req.evalDatasetId());
        requireFrozen(evalDataset, "回评集");
        if (dataset.getId().equals(evalDataset.getId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "回评集不能与训练集相同（当前都是「" + dataset.getName() + "」）："
                            + "训练集的一部分会被拿去训练，在它上面回评等于用练习题当考卷——"
                            + "自动回评的意义就是换一份集看泛化，请另选一份冻结的评测集");
        }
        DecisionCheckpointEntity base = checkpointService.require(req.baseCheckpointId());
        requirePath(base);
        requireSlot(base);

        List<DecisionDatasetItemEntity> rows = itemRepo.findByDatasetIdOrderByIdAsc(dataset.getId());
        if (rows.size() < 2) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "训练集「" + dataset.getName() + "」只有 " + rows.size() + " 条样本："
                            + "切不出「训练 + 验证」两边，跑完只能看到训练损失");
        }

        String outputPath = requireOutputPath(req);
        int epochs = req.epochs() == null ? props.getDefaultEpochs() : req.epochs();
        double lr = req.learningRate() == null ? props.getDefaultLearningRate() : req.learningRate();
        int batch = req.batchSize() == null ? props.getDefaultBatchSize() : req.batchSize();
        long trainSeed = req.trainSeed() == null ? props.getDefaultTrainSeed() : req.trainSeed();
        long splitSeed = req.splitSeed() == null ? props.getDefaultSplitSeed() : req.splitSeed();
        double ratio = req.trainRatio() == null ? props.getDefaultTrainRatio() : req.trainRatio();
        if (!FinetuneSplit.ratioInRange(ratio)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "训练占比取值 " + FinetuneSplit.MIN_TRAIN_RATIO + "-" + FinetuneSplit.MAX_TRAIN_RATIO
                            + "（当前 " + ratio + "）：两端都会让一边没有样本");
        }
        String launcher = req.launcher() == null || req.launcher().isBlank()
                ? props.getDefaultLauncher() : req.launcher().strip();

        String pythonPath = req.pythonPath() == null || req.pythonPath().isBlank()
                ? props.getPythonPath() : req.pythonPath().strip();
        scripts.requireRunnable(LabScripts.FINETUNE_ENTRY, pythonPath);
        long timeout = resolveTimeout(req.timeoutSec());
        concurrency.requireCapacity(props.getMaxConcurrentRuns(), "微调");

        FinetuneSplit.Split split = FinetuneSplit.of(
                rows.stream().map(DecisionDatasetItemEntity::getId).toList(), splitSeed, ratio);

        // 先把命令渲染出来（纯本地计算）：超参/产出目录是人在页面上填的，
        // 它们的问题该在这里报，而不是混进"无可用节点"这种要人去查别处的话里
        TrainScript.Spec spec = new TrainScript.Spec(pythonPath, base.getSourcePath(),
                base.getServeSlot(), outputPath, epochs, lr, batch, trainSeed, launcher);
        String command = TrainScript.render(spec);

        String nodeId = nodeRouter.route(req.nodeId(), null, parseCsv(req.requiredLabels()));
        nodeRouter.requireBundleCapable(nodeId);

        DecisionFinetuneEntity f = new DecisionFinetuneEntity();
        f.setDatasetId(dataset.getId());
        f.setDatasetName(dataset.getName());
        f.setDatasetVersion(dataset.getVersion());
        f.setQuestionSetVersion(dataset.getQuestionSetVersion());
        f.setItemCount(dataset.getItemCount());
        f.setTrainCount(split.trainCount());
        f.setValCount(split.valCount());
        f.setSplitSeed(splitSeed);
        f.setTrainRatio(ratio);
        f.setValItemIdsJson(toJson(split.valIds()));
        f.setEvalDatasetId(evalDataset.getId());
        f.setEvalDatasetName(evalDataset.getName());
        f.setEvalDatasetVersion(evalDataset.getVersion());
        f.setBaseCheckpointId(base.getId());
        f.setBaseCheckpointName(base.getName());
        f.setBaseServeSlot(base.getServeSlot());
        f.setBaseCheckpointPath(base.getSourcePath());
        f.setServeSlot(base.getServeSlot());
        f.setOutputPath(outputPath);
        f.setEpochs(epochs);
        f.setLearningRate(lr);
        f.setBatchSize(batch);
        f.setTrainSeed(trainSeed);
        f.setLauncher(launcher);
        f.setPythonPath(pythonPath);
        f.setNodeId(nodeId);
        f.setTimeoutSeconds(timeout);
        f.setCommandText(command);
        f.setStatus(DecisionFinetuneEntity.QUEUED);
        f.setCreatedBy(identityService.currentActor());
        f.setCreatedAt(Instant.now());
        DecisionFinetuneEntity saved = repo.save(f);
        log.info("微调触发: id={} base={} 训练集={}（{} 训练/{} 验证）回评集={} node={} by={} {}",
                saved.getId(), base.getName(), dataset.getName(), split.trainCount(), split.valCount(),
                evalDataset.getName(), nodeId, saved.getCreatedBy(), TrainScript.describe(spec));
        executor.submit(() -> run(saved.getId()));
        return view(saved);
    }

    private void requireFrozen(DecisionDatasetEntity dataset, String what) {
        if (!dataset.isFrozen()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    what + "「" + dataset.getName() + "」未冻结：训练与指标都只有在输入固定时才可比，请先冻结");
        }
        if (dataset.getItemCount() <= 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, what + "「" + dataset.getName() + "」没有条目");
        }
    }

    private static void requirePath(DecisionCheckpointEntity base) {
        if (base.getSourcePath() == null || base.getSourcePath().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "基座「" + base.getName() + "」未登记来源路径，节点无从加载");
        }
    }

    /**
     * 基座必须有槽位：产出服务在哪个槽位由它决定。
     *
     * <p>本 CAP 只换来源不改名字——槽位决定题面与原语，换槽位就是换了一个模型该答的题，
     * 那不是微调，是另一件事。所以这里跟着基座走，不让人选。</p>
     */
    private static void requireSlot(DecisionCheckpointEntity base) {
        if (base.getServeSlot() == null || base.getServeSlot().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "基座「" + base.getName() + "」未登记服务槽位——产出的槽位跟着基座走（只换来源不改名字），"
                            + "没有它就说不清这份权重将来替谁服务");
        }
    }

    private static String requireOutputPath(FinetuneTriggerRequest req) {
        String path = req.outputPath() == null ? null : req.outputPath().strip();
        if (path == null || path.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "产出目录不能为空：权重留在节点上，总得说清留在哪（服务端只收回指纹与指标）");
        }
        return path;
    }

    private long resolveTimeout(Long requested) {
        long max = Math.max(MIN_TIMEOUT_SEC, props.getMaxTimeoutSec());
        if (requested == null) {
            return Math.min(Math.max(MIN_TIMEOUT_SEC, props.getDefaultTimeoutSec()), max);
        }
        if (requested < MIN_TIMEOUT_SEC) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "超时不能小于 " + MIN_TIMEOUT_SEC + " 秒（当前 " + requested + "）：训练连加载权重都不止这个时间");
        }
        if (requested > max) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "超时不能超过 " + max + " 秒（配置项 devmind.decision-lab.max-timeout-sec，当前 " + requested + "）");
        }
        return requested;
    }

    // ---------------- 异步执行 ----------------

    /** 不用 @Transactional：见类注释（异步线程要看得见已提交的行） */
    void run(Long finetuneId) {
        DecisionFinetuneEntity f = repo.findById(finetuneId).orElse(null);
        if (f == null) {
            return;
        }
        f.setStatus(DecisionFinetuneEntity.RUNNING);
        f.setStartedAt(Instant.now());
        repo.save(f);

        String topic = String.valueOf(finetuneId);
        LabMarkers.Tap tap = new LabMarkers.Tap(
                line -> hub.publishLog(topic, line),
                item -> hub.publishEvent(topic, "item", item));
        tap.accept("[微调] #" + finetuneId + " 节点 " + f.getNodeId() + " · 基座 " + f.getBaseCheckpointName()
                + " @ " + f.getBaseServeSlot() + " → 产出 " + f.getOutputPath());
        tap.accept("[切分] 训练集「" + f.getDatasetName() + " v" + f.getDatasetVersion() + "」"
                + f.getTrainCount() + " 训练 / " + f.getValCount() + " 验证（切分种子 " + f.getSplitSeed()
                + "，判据已入库：报告的验证指标就是在后 " + f.getValCount() + " 条上算的）");
        tap.accept("[超参] " + TrainScript.describe(specOf(f)));
        tap.accept("[回评] 跑完将自动登记产物，并在「" + f.getEvalDatasetName() + " v"
                + f.getEvalDatasetVersion() + "」上评测一次（与训练集分开算，避免拿练习题当考卷）");
        tap.accept("[命令] " + f.getCommandText());

        Map<String, Object> report = null;
        Map<String, Object> fingerprint = null;
        try {
            StepSpec step = new StepSpec("决策微调", f.getCommandText(), null, CAPABILITY);
            AgentExecCommand.LabBundleRef bundle = new AgentExecCommand.LabBundleRef(
                    DecisionLabBundleProvider.KIND_FINETUNE, topic);
            StepResult r = stepRunner.runStep(f.getNodeId(), null, "lab-ft-" + finetuneId, 0, step,
                    Map.of(), null, bundle, f.getTimeoutSeconds(), tap);
            report = tap.report().orElse(null);
            fingerprint = tap.fingerprint().orElse(null);
            f.setExitCode(r.exitCode());
            if (r.ok()) {
                f.setStatus(DecisionFinetuneEntity.SUCCESS);
            } else {
                f.setStatus(DecisionFinetuneEntity.FAILED);
                f.setErrorSummary(truncate(r.error() == null ? "exit=" + r.exitCode() : r.error(), 2000));
            }
        } catch (Exception ex) {
            log.warn("微调 {} 异常: {}", finetuneId, ex.toString());
            f.setStatus(DecisionFinetuneEntity.FAILED);
            f.setExitCode(-1);
            f.setErrorSummary(truncate("微调异常: " + rootMessage(ex), 2000));
        } finally {
            persistReport(f, report, tap);
            f.setFingerprintJson(toJson(fingerprint));
            if (DecisionFinetuneEntity.FAILED.equals(f.getStatus())) {
                tap.accept("[微调] 失败：" + f.getErrorSummary());
            } else {
                tap.accept("[微调] 验证集指标 → " + EvalReport.summary(report));
                // 收尾两件事在"收尾日志"之前做：它们说的话要进 logs_text，也要在 WS 上被看到
                postSteps(f, fingerprint, tap);
            }
            f.setLogsText(tap.logText());
            f.setFinishedAt(Instant.now());
            repo.save(f);
            hub.done(topic, f.getStatus());
        }
    }

    /**
     * 报告落库。失败也要落（同评测）：报告可能已经打印出来了，进程只是收尾非零退出。
     */
    private void persistReport(DecisionFinetuneEntity f, Map<String, Object> report, LabMarkers.Tap tap) {
        if (report == null) {
            f.setReportStatus(tap.malformed() > 0
                    ? DecisionFinetuneEntity.REPORT_MALFORMED : DecisionFinetuneEntity.REPORT_MISSING);
            return;
        }
        f.setReportStatus(EvalReport.status(report));
        f.setMetricsJson(toJson(report));
        f.setHeadlineJson(toJson(EvalReport.headline(report)));
    }

    // ---------------- 收尾：登记产物 + 触发回评 ----------------

    private void postSteps(DecisionFinetuneEntity f, Map<String, Object> fingerprint, LabMarkers.Tap tap) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            f.setPostError("训练成功但未收到产物指纹（脚本未打印 " + LabMarkers.FINGERPRINT + " 行）："
                    + "权重在节点上 " + f.getOutputPath() + "，但没有 sha256 就无法登记成可放行的产物"
                    + "（输出目录会被下一轮训练覆盖，路径不算凭据）");
            tap.accept("[收尾] " + f.getPostError());
            return;
        }
        Long checkpointId;
        try {
            checkpointId = registerCheckpoint(f, fingerprint);
        } catch (Exception ex) {
            f.setPostError("训练成功但产物登记失败：" + rootMessage(ex));
            tap.accept("[收尾] " + f.getPostError());
            return;
        }
        tap.accept("[收尾] 已登记产物 #" + checkpointId + "「" + f.getCheckpointName()
                + "」（槽位 " + f.getServeSlot() + "，未验证：需人工看过指标后放行）");
        try {
            // fitTemperature=false：微调已在**验证切分**上拟合过温度（真正的 held-out），
            // 在这里再拟合一次是拿评测集当校准集，只会把 ECE 报得更漂亮
            EvalView view = evalService.trigger(new EvalTriggerRequest(checkpointId, f.getEvalDatasetId(),
                    f.getBaseCheckpointId(), f.getNodeId(), null, f.getPythonPath(), null, null,
                    Boolean.FALSE));
            f.setEvalId(view.id());
            tap.accept("[回评] 已触发评测 #" + view.id() + "：产物「" + f.getCheckpointName()
                    + "」×「" + f.getEvalDatasetName() + " v" + f.getEvalDatasetVersion()
                    + "」，对照基线「" + f.getBaseCheckpointName() + "」（实时日志走 /ws/decision-lab/evaluations/"
                    + view.id() + "）");
        } catch (Exception ex) {
            f.setPostError("产物已登记（#" + checkpointId + "「" + f.getCheckpointName()
                    + "」），但自动回评未触发：" + rootMessage(ex));
            tap.accept("[回评] " + f.getPostError());
        }
    }

    /**
     * 用指纹登记一条 FR-06 产物。
     *
     * <p><b>来源路径取指纹里的 path，而不是触发时填的产出目录</b>：脚本实际把权重写在哪，
     * 只有它自己知道（可能落在输出目录下的子目录里）。这份登记将来要能被边车加载，
     * 路径错一层就是"加载不到"——认脚本报回来的那一份，不认人填的那一份。</p>
     *
     * <p>指标与校准随产物一起写回（{@code attachEvalResult}）：产物行的指标是 FR-07 闸门
     * 放行前唯一要看的东西；温度参数来自验证切分的 held-out 拟合，正是"校准参数随 checkpoint
     * 元数据走"（FR-04）要的那个来源。</p>
     */
    private Long registerCheckpoint(DecisionFinetuneEntity f, Map<String, Object> fingerprint) {
        String path = str(fingerprint.get("path"));
        String sha = str(fingerprint.get("sha256"));
        if (path == null) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "指纹里没有 path（脚本用了与平台不一致的 " + LabMarkers.FINGERPRINT + " 结构）");
        }
        if (sha == null) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "指纹里没有 sha256：微调产物必须能被指认（输出目录会被下一轮训练覆盖）");
        }
        Long bytes = asLong(fingerprint.get("bytes"));
        CheckpointView view = checkpointService.create(new CheckpointRequest(
                checkpointName(f), f.getServeSlot(), DecisionCheckpointEntity.KIND_FINETUNED,
                path, f.getNodeId(), path, bytes, sha, null, checkpointNote(f)));
        f.setCheckpointId(view.id());
        f.setCheckpointName(view.name());
        checkpointService.attachEvalResult(view.id(), f.getMetricsJson(), calibrationJson(f));
        log.info("微调 {} 自动登记产物: id={} name={} slot={} path={} sha256={}", f.getId(),
                view.id(), view.name(), f.getServeSlot(), path, sha);
        return view.id();
    }

    /**
     * 产物名：{@code ft<微调 id>-<训练集名>}。
     *
     * <p>带上微调 id 是为了天然唯一（{@code CheckpointService.create} 拒绝重名），
     * 带上训练集名是为了在产物列表里一眼看出"这份是谁训出来的"——一串自动生成的 id 排在一起，
     * 人对不上它是哪次实验的产物。</p>
     */
    private static String checkpointName(DecisionFinetuneEntity f) {
        String base = "ft" + f.getId();
        String ds = f.getDatasetName() == null ? "" : f.getDatasetName().strip();
        if (ds.isEmpty()) {
            return base;
        }
        String name = base + "-" + ds;
        return name.length() <= MAX_NAME_CHARS ? name : name.substring(0, MAX_NAME_CHARS - 1) + "…";
    }

    private static String checkpointNote(DecisionFinetuneEntity f) {
        String note = "由微调任务 #" + f.getId() + " 自动登记：基座「" + f.getBaseCheckpointName()
                + "」，训练集「" + f.getDatasetName() + " v" + f.getDatasetVersion() + "」"
                + f.getTrainCount() + " 训练 / " + f.getValCount() + " 验证，验证指标见指标栏";
        return truncate(note, MAX_NOTE_CHARS);
    }

    /** 报告里的温度校准段落（没有 = null：回写时不动产物上已有的校准参数） */
    private String calibrationJson(DecisionFinetuneEntity f) {
        Map<String, Object> report = parseJson(f.getMetricsJson());
        Object calibration = report.get("calibration");
        return calibration instanceof Map<?, ?> ? toJson(calibration) : null;
    }

    // ---------------- 查询 ----------------

    public PageView<FinetuneView> list(Long datasetId, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 200);
        PageRequest pr = PageRequest.of(p, s);
        List<DecisionFinetuneEntity> rows;
        long total;
        if (datasetId != null) {
            rows = repo.findByDatasetIdOrderByIdDesc(datasetId, pr);
            total = repo.countByDatasetId(datasetId);
        } else {
            rows = repo.findAllByOrderByIdDesc(pr);
            total = repo.count();
        }
        return new PageView<>(rows.stream().map(this::view).toList(), total, p, s);
    }

    public FinetuneDetail detail(Long id) {
        DecisionFinetuneEntity f = require(id);
        Map<String, Object> report = parseJson(f.getMetricsJson());
        return new FinetuneDetail(view(f), report, perItem(report),
                mapOf(report, "calibration"), mapOf(report, "train"), valItemIds(f), f.getCommandText());
    }

    /** 人读日志（已剔 marker 行；机器载荷在详情里以结构化形式给出） */
    public String logs(Long id) {
        return require(id).getLogsText();
    }

    /** 删除历史；运行中拒绝（删掉正在跑的那条只会让执行线程写回一个已不存在的行） */
    public void delete(Long id) {
        DecisionFinetuneEntity f = require(id);
        if (DecisionFinetuneEntity.RUNNING.equals(f.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "微调运行中不可删除");
        }
        repo.delete(f);
    }

    public DecisionFinetuneEntity require(Long id) {
        return repo.findById(id).orElseThrow(() ->
                new DevMindException(ErrorCode.NOT_FOUND, "微调任务不存在: " + id));
    }

    // ---------------- 内部 ----------------

    private FinetuneView view(DecisionFinetuneEntity f) {
        return FinetuneView.of(f, parseJson(f.getHeadlineJson()));
    }

    /**
     * 行里存的就是渲染命令时用的那一套（不是从命令行反解出来的）。
     *
     * <p>只在日志里用；超参列理论上必非空（触发时全填了），但这里仍按默认值兜底——
     * 一行历史数据缺列不该让整条日志链 NPE。</p>
     */
    private TrainScript.Spec specOf(DecisionFinetuneEntity f) {
        return new TrainScript.Spec(f.getPythonPath(), f.getBaseCheckpointPath(), f.getBaseServeSlot(),
                f.getOutputPath(),
                f.getEpochs() == null ? props.getDefaultEpochs() : f.getEpochs(),
                f.getLearningRate() == null ? props.getDefaultLearningRate() : f.getLearningRate(),
                f.getBatchSize() == null ? props.getDefaultBatchSize() : f.getBatchSize(),
                f.getTrainSeed() == null ? props.getDefaultTrainSeed() : f.getTrainSeed(),
                f.getLauncher());
    }

    private List<Long> valItemIds(DecisionFinetuneEntity f) {
        if (f.getValItemIdsJson() == null || f.getValItemIdsJson().isBlank()) {
            return List.of();
        }
        try {
            List<Long> ids = mapper.readValue(f.getValItemIdsJson(), ID_LIST);
            return ids == null ? List.of() : ids;
        } catch (Exception ex) {
            // 切分明细读不出来 = 数据坏了，但详情页还得出得来（页面显示为空，列表上的条数照旧）
            log.warn("微调 {} 的验证切分读不出来: {}", f.getId(), ex.toString());
            return List.of();
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<>() {
            });
            return m == null ? Map.of() : m;
        } catch (Exception ex) {
            log.warn("微调报告解析失败: {}", ex.toString());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> perItem(Map<String, Object> report) {
        if (!(report.get("perItem") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> report, String key) {
        return report.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new DevMindException(ErrorCode.INTERNAL, "微调数据序列化失败: " + ex.getMessage());
        }
    }

    /** 指纹里的数字：JSON 里可能是 int/long/double，归一成 Long（缺了就是缺了） */
    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).strip();
        return s.isEmpty() ? null : s;
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String s = part.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
