package com.devmind.decisionlab.eval;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.decision.DecisionLabBundleProvider;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.checkpoint.CheckpointService;
import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import com.devmind.decisionlab.config.DecisionLabProperties;
import com.devmind.decisionlab.dataset.DatasetService;
import com.devmind.decisionlab.dataset.model.DecisionDatasetEntity;
import com.devmind.decisionlab.eval.dto.EvalDetail;
import com.devmind.decisionlab.eval.dto.EvalTriggerRequest;
import com.devmind.decisionlab.eval.dto.EvalView;
import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import com.devmind.decisionlab.eval.repo.DecisionEvaluationRepository;
import com.devmind.decisionlab.lab.LabMarkers;
import com.devmind.decisionlab.lab.LabScripts;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.execution.ws.ExecutionLogHub;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-56 FR-03 评测运行编排：触发（冻结集 + checkpoint + 节点路由 all fail-fast）→ 虚拟线程
 * 异步执行（runner exec 帧 + 执行包）→ 从日志流里捞报告落库 → 状态机 QUEUED/RUNNING/SUCCESS/FAILED，
 * 日志与逐题事件实时经统一执行底座的 {@link ExecutionLogHub} 广播。
 *
 * <p><b>为什么要有"评测跑一次"这条链</b>：CAP-55 把 laya 接进了产品路径之后，判断质量完全是
 * 黑盒——2026-09-22 那次真机实测里，{@code multilingual} 在分诊题面上对三组对照（含空召回）
 * 全判「重复」、置信度 0.93~0.99。模型的输出是自信而整齐的，看不出退化；能看出退化的只有
 * 数字，而数字需要一个固定输入、一个可复现的执行、以及一条"跟随机猜比怎么样"的基线。</p>
 *
 * <p><b>服务端不执行模型</b>（CAP-34 起服务端零执行）：本类只渲染命令、下发、收报告。
 * 权重、依赖、GPU 全在节点上；服务端看到的是指标、逐题明细与指纹。</p>
 *
 * <p><b>不在 @Transactional 里</b>（异步触发方法红线）：{@code save()} 自身事务即时提交，
 * 否则异步线程在另一条连接上看不到刚写的行，任务会永远停在 QUEUED。</p>
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    /** 单次评测的最小允许超时（秒）：比这更短基本必然半路被杀，与其让人等一个假失败不如直接拒绝 */
    private static final long MIN_TIMEOUT_SEC = 60;

    private static final String CAPABILITY = "decision-eval";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final DecisionEvaluationRepository repo;
    private final DatasetService datasetService;
    private final CheckpointService checkpointService;
    private final DecisionLabProperties props;
    private final LabScripts scripts;
    private final AgentNodeRouter nodeRouter;
    private final AgentNodeStepRunner stepRunner;
    private final ExecutionLogHub hub;
    private final IdentityService identityService;
    private final ObjectMapper mapper;

    public EvalService(DecisionEvaluationRepository repo, DatasetService datasetService,
                       CheckpointService checkpointService,
                       DecisionLabProperties props, LabScripts scripts, AgentNodeRouter nodeRouter,
                       AgentNodeStepRunner stepRunner, ExecutionLogHub hub,
                       IdentityService identityService, ObjectMapper mapper) {
        this.repo = repo;
        this.datasetService = datasetService;
        this.checkpointService = checkpointService;
        this.props = props;
        this.scripts = scripts;
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

    /**
     * 发起评测。
     *
     * <p>校验顺序是"先本地能判的、后要节点的"：数据集/checkpoint/脚本的问题不该等到路由完节点
     * 才报（那时错误信息里会混进"无可用节点"，把人的注意力引到错误的方向）。</p>
     */
    public EvalView trigger(EvalTriggerRequest req) {
        if (req == null || req.checkpointId() == null || req.datasetId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "checkpointId 与 datasetId 均必填");
        }
        DecisionCheckpointEntity ckpt = checkpointService.require(req.checkpointId());
        DecisionDatasetEntity dataset = datasetService.requireDataset(req.datasetId());
        requireFrozen(dataset);
        requirePath(ckpt, "被测 checkpoint");
        DecisionCheckpointEntity base = null;
        if (req.baseCheckpointId() != null) {
            if (req.baseCheckpointId().equals(ckpt.getId())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "对照基线不能选同一个 checkpoint");
            }
            base = checkpointService.require(req.baseCheckpointId());
            requirePath(base, "对照基线 checkpoint");
        }
        String pythonPath = req.pythonPath() == null || req.pythonPath().isBlank()
                ? props.getPythonPath() : req.pythonPath().strip();
        scripts.requireRunnable(LabScripts.EVAL_ENTRY, pythonPath);
        long timeout = resolveTimeout(req.timeoutSec());
        requireConcurrency();

        String nodeId = nodeRouter.route(req.nodeId(), null, parseCsv(req.requiredLabels()));
        // 执行包要 v14+：在触发阶段就说清，而不是留一条 FAILED 记录让人去翻日志
        nodeRouter.requireBundleCapable(nodeId);

        String command = EvalScript.render(new EvalScript.Spec(pythonPath, ckpt.getSourcePath(),
                ckpt.getServeSlot(), trimToNull(req.outputPath()),
                base == null ? null : base.getSourcePath(),
                base == null ? null : base.getServeSlot(),
                Boolean.TRUE.equals(req.fitTemperature())));

        DecisionEvaluationEntity e = new DecisionEvaluationEntity();
        e.setDatasetId(dataset.getId());
        e.setDatasetName(dataset.getName());
        e.setDatasetVersion(dataset.getVersion());
        e.setQuestionSetVersion(dataset.getQuestionSetVersion());
        e.setItemCount(dataset.getItemCount());
        e.setCheckpointId(ckpt.getId());
        e.setCheckpointName(ckpt.getName());
        e.setServeSlot(ckpt.getServeSlot());
        e.setCheckpointPath(ckpt.getSourcePath());
        if (base != null) {
            e.setBaseCheckpointId(base.getId());
            e.setBaseCheckpointName(base.getName());
            e.setBaseCheckpointPath(base.getSourcePath());
        }
        e.setNodeId(nodeId);
        e.setTimeoutSeconds(timeout);
        e.setCommandText(command);
        e.setStatus(DecisionEvaluationEntity.QUEUED);
        e.setCreatedBy(identityService.currentActor());
        e.setCreatedAt(Instant.now());
        DecisionEvaluationEntity saved = repo.save(e);
        log.info("评测触发: id={} ckpt={} dataset={} node={} by={}",
                saved.getId(), ckpt.getName(), dataset.getName(), nodeId, saved.getCreatedBy());
        executor.submit(() -> run(saved.getId()));
        return view(saved);
    }

    private void requireFrozen(DecisionDatasetEntity dataset) {
        if (!dataset.isFrozen()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "评测集「" + dataset.getName() + "」未冻结：指标只有在输入固定时才可比，请先冻结"
                            + "（冻结会校验对照组是否名副其实）");
        }
        if (dataset.getItemCount() <= 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "评测集「" + dataset.getName() + "」没有条目，无从评测");
        }
    }

    /** checkpoint 没有来源路径 = 节点无从加载（HF 仓库名或本地绝对路径都算，空的不算） */
    private void requirePath(DecisionCheckpointEntity ckpt, String what) {
        if (ckpt.getSourcePath() == null || ckpt.getSourcePath().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    what + "「" + ckpt.getName() + "」未登记来源路径，节点无从加载");
        }
    }

    private void requireConcurrency() {
        int limit = Math.max(1, props.getMaxConcurrentRuns());
        long active = repo.countByStatusIn(
                List.of(DecisionEvaluationEntity.QUEUED, DecisionEvaluationEntity.RUNNING));
        if (active >= limit) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "同时进行的评测/微调已达上限 " + limit + "（配置项 devmind.decision-lab.max-concurrent-runs）："
                            + "一次运行会独占节点的一个执行许可，跑太多会把节点许可占满");
        }
    }

    private long resolveTimeout(Long requested) {
        long max = Math.max(MIN_TIMEOUT_SEC, props.getMaxTimeoutSec());
        if (requested == null) {
            return Math.min(Math.max(MIN_TIMEOUT_SEC, props.getDefaultTimeoutSec()), max);
        }
        if (requested < MIN_TIMEOUT_SEC) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "超时不能小于 " + MIN_TIMEOUT_SEC + " 秒（当前 " + requested + "）");
        }
        if (requested > max) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "超时不能超过 " + max + " 秒（配置项 devmind.decision-lab.max-timeout-sec，当前 " + requested + "）");
        }
        return requested;
    }

    // ---------------- 异步执行 ----------------

    /** 不用 @Transactional：见类注释（异步线程要看得见已提交的行） */
    void run(Long evalId) {
        DecisionEvaluationEntity e = repo.findById(evalId).orElse(null);
        if (e == null) {
            return;
        }
        e.setStatus(DecisionEvaluationEntity.RUNNING);
        e.setStartedAt(Instant.now());
        repo.save(e);

        String topic = String.valueOf(evalId);
        LabMarkers.Tap tap = new LabMarkers.Tap(
                line -> hub.publishLog(topic, line),
                item -> hub.publishEvent(topic, "item", item));
        tap.accept("[评测] #" + evalId + " 节点 " + e.getNodeId() + " · "
                + e.getCheckpointName() + " @ " + e.getServeSlot()
                + " × " + e.getDatasetName() + " v" + e.getDatasetVersion()
                + "（" + e.getItemCount() + " 条）");
        tap.accept("[命令] " + e.getCommandText());

        Map<String, Object> report = null;
        try {
            StepSpec step = new StepSpec("决策评测", e.getCommandText(), null, CAPABILITY);
            AgentExecCommand.LabBundleRef bundle = new AgentExecCommand.LabBundleRef(
                    DecisionLabBundleProvider.KIND_EVALUATION, topic);
            StepResult r = stepRunner.runStep(e.getNodeId(), null, "lab-eval-" + evalId, 0, step,
                    Map.of(), null, bundle, e.getTimeoutSeconds(), tap);
            report = tap.report().orElse(null);
            e.setExitCode(r.exitCode());
            if (r.ok()) {
                e.setStatus(DecisionEvaluationEntity.SUCCESS);
            } else {
                e.setStatus(DecisionEvaluationEntity.FAILED);
                e.setErrorSummary(truncate(r.error() == null ? "exit=" + r.exitCode() : r.error(), 2000));
            }
        } catch (Exception ex) {
            log.warn("评测 {} 异常: {}", evalId, ex.toString());
            e.setStatus(DecisionEvaluationEntity.FAILED);
            e.setExitCode(-1);
            e.setErrorSummary(truncate("评测异常: " + rootMessage(ex), 2000));
        } finally {
            persistReport(e, report, tap);
            tap.accept("[评测] " + (DecisionEvaluationEntity.FAILED.equals(e.getStatus())
                    ? "失败：" + e.getErrorSummary() : EvalReport.summary(report)));
            e.setLogsText(tap.logText());
            e.setFinishedAt(Instant.now());
            repo.save(e);
            hub.done(topic, e.getStatus());
        }
    }

    /**
     * 报告落库。
     *
     * <p>失败也要落：报告可能在人读的意义上"跑完了"（报告已打印）而进程因收尾步骤非零退出。
     * 把已有证据丢掉，只会让人重跑一次拿同样的东西。</p>
     */
    private void persistReport(DecisionEvaluationEntity e, Map<String, Object> report, LabMarkers.Tap tap) {
        e.setItemCount(report == null ? e.getItemCount()
                : EvalReport.itemCount(report, e.getItemCount() == null ? 0 : e.getItemCount()));
        if (report == null) {
            e.setReportStatus(tap.malformed() > 0
                    ? DecisionEvaluationEntity.REPORT_MALFORMED : DecisionEvaluationEntity.REPORT_MISSING);
            return;
        }
        e.setReportStatus(EvalReport.status(report));
        e.setReportJson(toJson(report));
        e.setHeadlineJson(toJson(EvalReport.headline(report)));
    }

    // ---------------- 查询 ----------------

    public PageView<EvalView> list(Long datasetId, Long checkpointId, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 200);
        PageRequest pr = PageRequest.of(p, s);
        List<DecisionEvaluationEntity> rows;
        long total;
        if (datasetId != null && checkpointId != null) {
            rows = repo.findByDatasetIdAndCheckpointIdOrderByIdDesc(datasetId, checkpointId, pr);
            total = repo.countByDatasetIdAndCheckpointId(datasetId, checkpointId);
        } else if (datasetId != null) {
            rows = repo.findByDatasetIdOrderByIdDesc(datasetId, pr);
            total = repo.countByDatasetId(datasetId);
        } else if (checkpointId != null) {
            rows = repo.findByCheckpointIdOrderByIdDesc(checkpointId, pr);
            total = repo.countByCheckpointId(checkpointId);
        } else {
            rows = repo.findAllByOrderByIdDesc(pr);
            total = repo.count();
        }
        return new PageView<>(rows.stream().map(this::view).toList(), total, p, s);
    }

    public EvalDetail detail(Long id) {
        DecisionEvaluationEntity e = require(id);
        Map<String, Object> report = parseReport(e.getReportJson());
        return new EvalDetail(view(e), report, EvalReport.byCaseGroup(report),
                perItem(report), mapOf(report, "calibration"), mapOf(report, "compare"),
                e.getCommandText());
    }

    public String logs(Long id) {
        return require(id).getLogsText();
    }

    /** 删除历史；运行中拒绝（删掉正在跑的那条只会让执行线程写回一个已不存在的行） */
    public void delete(Long id) {
        DecisionEvaluationEntity e = require(id);
        if (DecisionEvaluationEntity.RUNNING.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "评测运行中不可删除");
        }
        repo.delete(e);
    }

    public DecisionEvaluationEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "评测记录不存在: " + id));
    }

    // ---------------- 内部 ----------------

    private EvalView view(DecisionEvaluationEntity e) {
        return EvalView.of(e, parseReport(e.getHeadlineJson()));
    }

    private Map<String, Object> parseReport(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = mapper.readValue(json, new tools.jackson.core.type.TypeReference<>() {
            });
            return m == null ? Map.of() : m;
        } catch (Exception ex) {
            // 落库时的报告读不出来 = 数据坏了，但列表还得出得来（详情页会显示空报告）
            log.warn("评测报告解析失败: {}", ex.toString());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> perItem(Map<String, Object> report) {
        Object v = report.get("perItem");
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> report, String key) {
        return report.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new DevMindException(ErrorCode.INTERNAL, "评测报告序列化失败: " + ex.getMessage());
        }
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
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
