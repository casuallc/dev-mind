package com.devmind.deploy.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.build.model.BuildEntity;
import com.devmind.build.service.BuildService;
import com.devmind.deploy.dto.CreateDeploymentRequest;
import com.devmind.deploy.dto.DeployStepRequest;
import com.devmind.deploy.dto.DeploymentView;
import com.devmind.deploy.dto.StepView;
import com.devmind.deploy.model.DeploymentEntity;
import com.devmind.deploy.model.DeploymentStepEntity;
import com.devmind.deploy.model.DeployStep;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.deploy.repo.DeploymentStepRepository;
import com.devmind.deploy.ws.DeployHub;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.ProjectService;
import com.devmind.project.model.ProjectServerEntity;
import com.devmind.serveradapter.service.ServerOperationService;
import com.devmind.serveradapter.spi.ExecResult;

/**
 * CAP-09 部署编排：创建（渲染计划可见 + 幂等 FR-04）→ 异步执行（逐步走 CAP-07 模板白名单 capability=deploy，
 * 备份步捕获 backup= 行 → backup_ref）→ 任一步失败自动回滚（FR-03）→ 状态机
 * PLANNED/RUNNING/SUCCESS/FAILED/ROLLED_BACK → 通知（FR-06，成功 P1 / 失败·回滚 P0）。
 * 关键陷阱同构建：execute() 不标 @Transactional，save() 自身事务即时提交，否则异步 run() 看不到未提交行。
 */
@Service
public class DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);
    private static final Pattern BACKUP = Pattern.compile("(?im)^backup[:=]\\s*(.+)$");

    private final ExecutorService deployExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final DeploymentRepository repo;
    private final DeploymentStepRepository stepRepo;
    private final DeployConfigService configService;
    private final ProjectService projectService;
    private final ServerOperationService serverOpService;
    private final BuildService buildService;
    private final NotificationService notificationService;
    private final DeployHub hub;
    private final ObjectMapper mapper;

    public DeploymentService(DeploymentRepository repo,
                             DeploymentStepRepository stepRepo,
                             DeployConfigService configService,
                             ProjectService projectService,
                             ServerOperationService serverOpService,
                             BuildService buildService,
                             NotificationService notificationService,
                             DeployHub hub,
                             ObjectMapper mapper) {
        this.repo = repo;
        this.stepRepo = stepRepo;
        this.configService = configService;
        this.projectService = projectService;
        this.serverOpService = serverOpService;
        this.buildService = buildService;
        this.notificationService = notificationService;
        this.hub = hub;
        this.mapper = mapper;
    }

    @PreDestroy
    public void shutdown() {
        deployExecutor.shutdownNow();
    }

    // ---------------- 创建（FR-01/04） ----------------

    public DeploymentView create(CreateDeploymentRequest req) {
        projectService.requireProject(req.projectId());
        serverOpService.requireServer(req.serverId());
        String projectId = req.projectId();
        boolean confirmRequired = req.confirmRequired() != null && req.confirmRequired();
        boolean force = req.force() != null && req.force();

        String artifact = null;
        if (req.buildId() != null) {
            BuildEntity build = buildService.requireBuild(req.buildId());
            if (build.getArtifactRef() == null || build.getArtifactRef().isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "构建 " + req.buildId() + " 未登记产物（artifactRef 为空），无法部署");
            }
            artifact = build.getArtifactRef();
        }

        // FR-04 幂等：同 project+server+build 的 PLANNED/RUNNING/SUCCESS 视为重复部署
        if (!force && req.buildId() != null) {
            List<DeploymentEntity> dup = repo.findByProjectIdAndServerIdAndBuildIdAndStatusIn(
                    projectId, req.serverId(), req.buildId(),
                    List.of(DeploymentEntity.PLANNED, DeploymentEntity.RUNNING, DeploymentEntity.SUCCESS));
            if (!dup.isEmpty()) {
                DeploymentEntity first = dup.get(0);
                throw new DevMindException(ErrorCode.CONFLICT,
                        "该构建已存在部署记录 #" + first.getId() + "（" + first.getStatus() + "），如需重新部署请传 force=true");
            }
        }

        List<DeployStep> plan;
        if (req.plan() != null && !req.plan().isEmpty()) {
            plan = req.plan().stream()
                    .map(s -> new DeployStep(s.name(), s.type(), s.templateCode(),
                            s.params() == null ? Map.of() : new LinkedHashMap<>(s.params())))
                    .toList();
        } else {
            plan = parseSteps(configService.requireConfig(projectId).getStepsJson());
        }
        if (plan.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "部署计划为空");
        }

        DeploymentEntity d = new DeploymentEntity();
        d.setProjectId(projectId);
        d.setRequirementId(req.requirementId());
        d.setServerId(req.serverId());
        d.setBuildId(req.buildId());
        d.setEnv(req.env() == null || req.env().isBlank() ? "test" : req.env());
        d.setPlanJson(writeJson(plan));
        d.setStatus(DeploymentEntity.PLANNED);
        d.setCurrentStep(0);
        d.setConfirmRequired(confirmRequired);
        d.setConfirmed(!confirmRequired);
        d.setCreatedBy("user");
        d.setCreatedAt(Instant.now());
        DeploymentEntity saved = repo.save(d);

        int seq = 1;
        for (DeployStep s : plan) {
            stepRepo.save(newStep(saved.getId(), seq++, s));
        }
        return toView(saved);
    }

    /** FR-07 确认门：confirmRequired 的部署需先确认才能执行 */
    public DeploymentView confirm(Long id) {
        DeploymentEntity d = require(id);
        if (!DeploymentEntity.PLANNED.equals(d.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "只有待执行（PLANNED）的部署可确认");
        }
        d.setConfirmed(true);
        repo.save(d);
        return toView(d);
    }

    /** 执行：不用 @Transactional（同构建），save 自身事务即时提交后异步 run() */
    public DeploymentView execute(Long id) {
        DeploymentEntity d = require(id);
        if (!DeploymentEntity.PLANNED.equals(d.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "只有待执行（PLANNED）的部署可执行");
        }
        if (d.isConfirmRequired() && !d.isConfirmed()) {
            throw new DevMindException(ErrorCode.CONFLICT, "该部署需先确认（POST /deployments/" + id + "/confirm）");
        }
        d.setStatus(DeploymentEntity.RUNNING);
        d.setStartedAt(Instant.now());
        repo.save(d);
        deployExecutor.submit(() -> run(d.getId()));
        return toView(d);
    }

    /** 手动回滚（FR-03）：生成 rollback_of 指向原部署的新部署单（计划=配置的回滚步骤），并立即执行 */
    public DeploymentView rollback(Long id) {
        DeploymentEntity d = require(id);
        if (DeploymentEntity.PLANNED.equals(d.getStatus()) || DeploymentEntity.RUNNING.equals(d.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "进行中的部署不可回滚");
        }
        if (DeploymentEntity.ROLLED_BACK.equals(d.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "该部署已回滚");
        }
        List<DeployStep> rb = rollbackStepsOf(d);
        if (rb.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "项目未配置回滚步骤，无法手动回滚");
        }
        DeploymentEntity nd = new DeploymentEntity();
        nd.setProjectId(d.getProjectId());
        nd.setRequirementId(d.getRequirementId());
        nd.setServerId(d.getServerId());
        nd.setBuildId(d.getBuildId());
        nd.setEnv(d.getEnv());
        nd.setPlanJson(writeJson(rb));
        nd.setStatus(DeploymentEntity.PLANNED);
        nd.setCurrentStep(0);
        nd.setRollbackOf(d.getId());
        nd.setConfirmRequired(false);
        nd.setConfirmed(true);
        nd.setCreatedBy("rollback");
        nd.setCreatedAt(Instant.now());
        DeploymentEntity saved = repo.save(nd);
        int seq = 1;
        for (DeployStep s : rb) {
            stepRepo.save(newStep(saved.getId(), seq++, s));
        }
        return execute(saved.getId()); // 自动执行回滚
    }

    // ---------------- 异步执行 ----------------

    private void run(Long deploymentId) {
        DeploymentEntity d = repo.findById(deploymentId).orElse(null);
        if (d == null) {
            return;
        }
        List<DeployStep> plan = parseSteps(d.getPlanJson());
        List<DeploymentStepEntity> steps = stepRepo.findByDeploymentIdOrderBySeqAsc(deploymentId);
        StringBuilder logs = new StringBuilder();
        Consumer<String> sink = line -> {
            synchronized (logs) {
                logs.append(line).append('\n');
            }
            hub.publishLog(deploymentId, line);
        };
        String artifact = resolveArtifact(d);
        // 手动回滚部署单使用原始部署的备份引用
        String backupRef = d.getRollbackOf() != null ? originalBackupRef(d) : d.getBackupRef();

        boolean ok = true;
        String err = null;
        try {
            for (int i = 0; i < plan.size(); i++) {
                DeployStep s = plan.get(i);
                int seq = i + 1;
                DeploymentStepEntity se = stepBySeq(steps, seq);
                if (se == null) {
                    se = newStep(d.getId(), seq, s);
                    stepRepo.save(se);
                    steps.add(se);
                }
                se.setStatus(DeploymentStepEntity.RUNNING);
                se.setStartedAt(Instant.now());
                se.setDetail(null);
                d.setCurrentStep(seq);
                repo.save(d);
                stepRepo.save(se);
                hub.publishStep(deploymentId, toStepView(se));
                sink.accept("===== 步骤 " + seq + "/" + plan.size() + " · " + label(s) + " =====");

                ExecResult r = execStep(d, s, backupRef, artifact);
                streamOut(sink, r.stdout(), r.stderr());
                if (r.success()) {
                    se.setStatus(DeploymentStepEntity.SUCCESS);
                    se.setFinishedAt(Instant.now());
                    if ("backup".equalsIgnoreCase(s.type())) {
                        String ref = captureBackup(logs.toString());
                        if (ref != null) {
                            d.setBackupRef(ref);
                            backupRef = ref;
                            repo.save(d);
                            sink.accept("[备份登记] " + ref);
                        }
                    }
                } else {
                    ok = false;
                    err = failureReason(r);
                    se.setStatus(DeploymentStepEntity.FAILED);
                    se.setDetail(truncate(err, 500));
                    se.setFinishedAt(Instant.now());
                    sink.accept("[部署失败] " + err);
                }
                stepRepo.save(se);
                hub.publishStep(deploymentId, toStepView(se));
                flushLogs(d, logs);
                if (!ok) {
                    break;
                }
            }

            if (ok) {
                // 手动回滚部署单：全部回滚步骤成功 → ROLLED_BACK（指向原始部署）
                d.setStatus(d.getRollbackOf() != null ? DeploymentEntity.ROLLED_BACK : DeploymentEntity.SUCCESS);
                repo.save(d);
                if (d.getRollbackOf() != null) {
                    notify(d, NotificationLevel.P1, "回滚成功 #" + d.getId(),
                            "原部署 #" + d.getRollbackOf() + " 已回滚到备份");
                } else {
                    notify(d, NotificationLevel.P1, "部署成功 #" + d.getId(),
                            "服务器 " + d.getServerId() + " · 环境 " + d.getEnv()
                                    + (d.getBackupRef() == null ? "" : " · 备份 " + d.getBackupRef()));
                }
            } else if (d.getRollbackOf() == null) {
                // FR-03 自动回滚：无回滚步骤时直接 FAILED
                List<DeployStep> rbSteps = rollbackStepsOf(d);
                if (rbSteps.isEmpty()) {
                    d.setStatus(DeploymentEntity.FAILED);
                    d.setErrorSummary(truncate(err, 2000) + "（未配置回滚步骤）");
                    repo.save(d);
                    notify(d, NotificationLevel.P0, "部署失败 #" + d.getId(), "原因: " + err + "（未配置回滚步骤）");
                } else {
                    sink.accept("[回滚] 部署失败，开始自动回滚…");
                    boolean rbOk = runRollback(d, rbSteps, backupRef, logs, sink);
                    if (rbOk) {
                        d.setStatus(DeploymentEntity.ROLLED_BACK);
                        d.setErrorSummary(truncate(err, 2000));
                        repo.save(d);
                        notify(d, NotificationLevel.P0, "部署失败已自动回滚 #" + d.getId(), "原因: " + err + "（已恢复备份）");
                    } else {
                        d.setStatus(DeploymentEntity.FAILED);
                        d.setErrorSummary("部署失败且回滚失败: " + err);
                        repo.save(d);
                        notify(d, NotificationLevel.P0, "部署失败且回滚失败 #" + d.getId(), "原因: " + err);
                    }
                }
            } else {
                d.setStatus(DeploymentEntity.FAILED);
                d.setErrorSummary("回滚失败: " + err);
                repo.save(d);
                notify(d, NotificationLevel.P0, "回滚失败 #" + d.getId(), "原因: " + err);
            }
        } catch (Exception e) {
            log.warn("部署 {} 异常: {}", deploymentId, e.toString());
            d.setStatus(DeploymentEntity.FAILED);
            d.setErrorSummary("部署异常: " + rootMessage(e));
            repo.save(d);
            notify(d, NotificationLevel.P0, "部署异常 #" + d.getId(), rootMessage(e));
        } finally {
            d.setLogsText(logs.toString());
            d.setFinishedAt(Instant.now());
            repo.save(d);
            hub.done(deploymentId, d.getStatus());
        }
    }

    /** 执行回滚步骤（追加为当前部署单的新步骤，逐项可见）；全部成功才返回 true */
    private boolean runRollback(DeploymentEntity d, List<DeployStep> rb, String backupRef,
                                StringBuilder logs, Consumer<String> sink) {
        List<DeploymentStepEntity> existing = stepRepo.findByDeploymentIdOrderBySeqAsc(d.getId());
        int base = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getSeq();
        String artifact = resolveArtifact(d);
        boolean allOk = true;
        for (int i = 0; i < rb.size(); i++) {
            DeployStep s = rb.get(i);
            int seq = base + i + 1;
            DeploymentStepEntity se = newStep(d.getId(), seq, s);
            se.setStatus(DeploymentStepEntity.RUNNING);
            se.setStartedAt(Instant.now());
            stepRepo.save(se);
            hub.publishStep(d.getId(), toStepView(se));
            sink.accept("===== 回滚步骤 " + (i + 1) + "/" + rb.size() + " · " + label(s) + " =====");
            ExecResult r = execStep(d, s, backupRef, artifact);
            streamOut(sink, r.stdout(), r.stderr());
            if (r.success()) {
                se.setStatus(DeploymentStepEntity.SUCCESS);
                se.setFinishedAt(Instant.now());
            } else {
                allOk = false;
                String e = failureReason(r);
                se.setStatus(DeploymentStepEntity.FAILED);
                se.setDetail(truncate(e, 500));
                se.setFinishedAt(Instant.now());
                sink.accept("[回滚失败] " + e);
            }
            stepRepo.save(se);
            hub.publishStep(d.getId(), toStepView(se));
            if (!allOk) {
                break;
            }
        }
        return allOk;
    }

    private ExecResult execStep(DeploymentEntity d, DeployStep s, String backupRef, String artifact) {
        Map<String, String> p = new LinkedHashMap<>();
        if (s.params() != null) {
            p.putAll(s.params());
        }
        p.put("env", d.getEnv() == null ? "" : d.getEnv());
        p.put("projectId", d.getProjectId());
        p.put("serverId", String.valueOf(d.getServerId()));
        p.put("artifact", artifact == null ? "" : artifact);
        p.put("backup", backupRef == null ? "" : backupRef);
        try {
            return serverOpService.execute(d.getServerId(), s.templateCode(), p, "deploy");
        } catch (Exception e) {
            return new ExecResult(-1, false, "", rootMessage(e), 0);
        }
    }

    // ---------------- 查询 ----------------

    public DeploymentEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "部署记录不存在: " + id));
    }

    public DeploymentView get(Long id) {
        return toView(require(id));
    }

    public List<DeploymentView> history(String projectId, String status) {
        List<DeploymentEntity> list = status == null || status.isBlank()
                ? repo.findByProjectIdOrderByCreatedAtDesc(projectId)
                : repo.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, status.trim().toUpperCase());
        return list.stream().map(this::toView).toList();
    }

    public String logs(Long id) {
        return require(id).getLogsText();
    }

    public void delete(Long id) {
        DeploymentEntity d = require(id);
        if (DeploymentEntity.RUNNING.equals(d.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "部署运行中不可删除");
        }
        stepRepo.deleteAll(stepRepo.findByDeploymentIdOrderBySeqAsc(id));
        repo.delete(d);
    }

    // ---------------- 视图 ----------------

    public DeploymentView toView(DeploymentEntity d) {
        List<DeployStepRequest> planView = parseSteps(d.getPlanJson()).stream()
                .map(s -> new DeployStepRequest(s.name(), s.type(), s.templateCode(), s.params()))
                .toList();
        List<StepView> steps = stepRepo.findByDeploymentIdOrderBySeqAsc(d.getId()).stream()
                .map(this::toStepView).toList();
        return new DeploymentView(d.getId(), d.getProjectId(), d.getRequirementId(), d.getServerId(), d.getBuildId(),
                d.getEnv(), d.getStatus(), d.getCurrentStep(), d.getBackupRef(), d.getRollbackOf(),
                d.isConfirmRequired(), d.isConfirmed(), d.getErrorSummary(), d.getCreatedBy(),
                d.getStartedAt(), d.getFinishedAt(), d.getCreatedAt(), planView, steps);
    }

    // ---------------- 内部 ----------------

    private String resolveArtifact(DeploymentEntity d) {
        if (d.getBuildId() != null) {
            try {
                BuildEntity b = buildService.requireBuild(d.getBuildId());
                return b.getArtifactRef() == null ? "" : b.getArtifactRef();
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }

    private String originalBackupRef(DeploymentEntity d) {
        if (d.getRollbackOf() == null) {
            return null;
        }
        return repo.findById(d.getRollbackOf()).map(DeploymentEntity::getBackupRef).orElse(null);
    }

    /** 项目配置的回滚步骤；未配置时返回空列表（自动回滚降级为直接 FAILED） */
    private List<DeployStep> rollbackStepsOf(DeploymentEntity d) {
        try {
            return parseSteps(configService.requireConfig(d.getProjectId()).getRollbackStepsJson());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 失败原因：优先 stderr 首行，其次 stdout 末行（真实启动失败常打 stdout），再退化为 exit 码 */
    private String failureReason(ExecResult r) {
        String e = firstLine(r.stderr());
        if (e != null && !e.isBlank()) {
            return e;
        }
        e = lastLine(r.stdout());
        if (e != null && !e.isBlank()) {
            return e;
        }
        return "exit=" + r.exitCode();
    }

    /** 备份步在日志中输出 backup= 或 backup: 行即登记备份引用（最后一个生效） */
    private String captureBackup(String logs) {
        if (logs == null) {
            return null;
        }
        Matcher m = BACKUP.matcher(logs);
        String last = null;
        while (m.find()) {
            last = m.group(1).trim();
        }
        return last == null || last.isBlank() ? null : last;
    }

    private void flushLogs(DeploymentEntity d, StringBuilder logs) {
        synchronized (logs) {
            d.setLogsText(logs.toString());
        }
        repo.save(d);
    }

    private void streamOut(Consumer<String> sink, String stdout, String stderr) {
        if (stdout != null && !stdout.isBlank()) {
            for (String l : stdout.split("\\R")) {
                sink.accept(l);
            }
        }
        if (stderr != null && !stderr.isBlank()) {
            for (String l : stderr.split("\\R")) {
                sink.accept("[stderr] " + l);
            }
        }
    }

    private void notify(DeploymentEntity d, NotificationLevel level, String title, String body) {
        try {
            notificationService.emit(new NotificationDraft(level, "deploy", title, body,
                    "deployment", String.valueOf(d.getId()), List.of()));
        } catch (Exception e) {
            log.warn("部署通知发送失败: {}", e.getMessage());
        }
    }

    private DeploymentStepEntity stepBySeq(List<DeploymentStepEntity> steps, int seq) {
        for (DeploymentStepEntity s : steps) {
            if (s.getSeq() != null && s.getSeq() == seq) {
                return s;
            }
        }
        return null;
    }

    private DeploymentStepEntity newStep(Long deploymentId, int seq, DeployStep s) {
        DeploymentStepEntity se = new DeploymentStepEntity();
        se.setDeploymentId(deploymentId);
        se.setSeq(seq);
        se.setName(s.name());
        se.setType(s.type());
        se.setStatus(DeploymentStepEntity.PENDING);
        return se;
    }

    private StepView toStepView(DeploymentStepEntity e) {
        return new StepView(e.getId(), e.getSeq(), e.getName(), e.getType(), e.getStatus(),
                e.getDetail(), e.getStartedAt(), e.getFinishedAt());
    }

    private String label(DeployStep s) {
        return s.name() == null || s.name().isBlank() ? s.type() : s.name();
    }

    private List<DeployStep> parseSteps(String json) {
        return configService.parse(json);
    }

    private String writeJson(List<DeployStep> steps) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (DeployStep s : steps) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.name());
                m.put("type", s.type());
                m.put("templateCode", s.templateCode());
                m.put("params", s.params() == null ? Map.of() : s.params());
                out.add(m);
            }
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "部署计划序列化失败");
        }
    }

    private String firstLine(String s) {
        if (s == null) {
            return null;
        }
        for (String l : s.split("\\R")) {
            if (!l.isBlank()) {
                return l.trim();
            }
        }
        return null;
    }

    private String lastLine(String s) {
        if (s == null) {
            return null;
        }
        String last = null;
        for (String l : s.split("\\R")) {
            if (!l.isBlank()) {
                last = l.trim();
            }
        }
        return last;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
