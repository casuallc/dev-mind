package com.devmind.flow;

import com.devmind.build.dto.BuildView;
import com.devmind.build.dto.TriggerRequest;
import com.devmind.build.model.BuildEntity;
import com.devmind.build.repo.BuildRepository;
import com.devmind.build.service.BuildService;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.deploy.dto.CreateDeploymentRequest;
import com.devmind.deploy.dto.DeploymentView;
import com.devmind.deploy.event.DeploymentCompletedEvent;
import com.devmind.deploy.model.DeployConfigEntity;
import com.devmind.deploy.model.DeploymentEntity;
import com.devmind.deploy.repo.DeployConfigRepository;
import com.devmind.deploy.repo.DeploymentRepository;
import com.devmind.deploy.service.DeploymentService;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.project.repo.BuildStepRepository;
import com.devmind.project.repo.EnvironmentRepository;
import com.devmind.project.repo.WorkItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 执行链编排器（CAP-17）：WI DONE → 自动构建 → 构建成功自动部署测试环境。
 * 语义边界——执行自动化，高风险决策归人：生产部署与发版永远人工触发，
 * 编排器只负责"WI DONE → 构建 → TEST 环境部署"这段安全区。
 *
 * <p>降级规则：缺一环就停（未配构建步骤/无产物/无 TEST 环境/无部署步骤 → 跳过或降级通知），
 * 不在事件总线上抛错。构建并发满（409）时本轮跳过，每次 build.completed 后补扫重试。</p>
 *
 * <p>分支假设：WI 验收前代码已由人合并回主干，自动构建走主库 HEAD（merge 自动化属后续能力）。</p>
 */
@Component
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final WorkItemService workItemService;
    private final WorkItemRepository workItemRepo;
    private final BuildStepRepository buildStepRepo;
    private final BuildRepository buildRepo;
    private final BuildService buildService;
    private final DeploymentService deploymentService;
    private final DeployConfigRepository deployConfigRepo;
    private final DeploymentRepository deploymentRepo;
    private final EnvironmentRepository environmentRepo;
    private final NotificationService notificationService;

    public PipelineOrchestrator(WorkItemService workItemService,
                                WorkItemRepository workItemRepo,
                                BuildStepRepository buildStepRepo,
                                BuildRepository buildRepo,
                                BuildService buildService,
                                DeploymentService deploymentService,
                                DeployConfigRepository deployConfigRepo,
                                DeploymentRepository deploymentRepo,
                                EnvironmentRepository environmentRepo,
                                NotificationService notificationService) {
        this.workItemService = workItemService;
        this.workItemRepo = workItemRepo;
        this.buildStepRepo = buildStepRepo;
        this.buildRepo = buildRepo;
        this.buildService = buildService;
        this.deploymentService = deploymentService;
        this.deployConfigRepo = deployConfigRepo;
        this.deploymentRepo = deploymentRepo;
        this.environmentRepo = environmentRepo;
        this.notificationService = notificationService;
    }

    /** FR-01：WI 翻转为 DONE → 幂等触发自动构建。 */
    @EventListener
    public void onWorkItemStatusChanged(SimpleDomainEvent event) {
        if (!"workitem.status.changed".equals(event.type()) || event.workItemId() == null) {
            return;
        }
        try {
            WorkItemEntity wi = workItemService.requireById(event.workItemId());
            if (PipelineEligibility.buildable(wi.getType(), wi.getStatus())) {
                tryTriggerBuild(event.projectId(), wi.getId(), wi.getSeq());
            }
        } catch (Exception e) {
            log.warn("执行链处理状态变更事件失败(不阻塞): wi={} err={}", event.workItemId(), e.getMessage());
        }
    }

    /** FR-02/03：构建完成 → 成功则自动部署 TEST 环境；无论成败都补扫一次待构建 WI（并发释放重试）。 */
    @EventListener
    public void onBuildCompleted(SimpleDomainEvent event) {
        if (!"build.completed".equals(event.type())) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(event.success()) && event.workItemId() != null
                    && event.entityId() != null) {
                autoDeployTestEnv(event.projectId(), event.workItemId(), Long.valueOf(event.entityId()));
            }
        } catch (Exception e) {
            log.warn("执行链自动部署失败(不阻塞): build={} err={}", event.entityId(), e.getMessage());
        }
        try {
            retryPendingBuilds(event.projectId());
        } catch (Exception e) {
            log.warn("执行链补扫构建失败(不阻塞): project={} err={}", event.projectId(), e.getMessage());
        }
    }

    /** FR-05：测试环境部署成功 → 通知链路完成，等人验收/发版。 */
    @EventListener
    public void onDeploymentCompleted(DeploymentCompletedEvent event) {
        if (!event.success()) {
            return;
        }
        try {
            DeploymentEntity d = deploymentRepo.findById(event.deploymentId()).orElse(null);
            if (d == null || d.getWorkItemId() == null) {
                return;
            }
            WorkItemEntity wi = workItemService.requireById(d.getWorkItemId());
            notify(NotificationLevel.P1, "pipeline.completed",
                    "WI-" + wi.getSeq() + " 构建+部署链路完成",
                    "工作单元「" + wi.getTitle() + "」已构建并部署到测试环境，请验收；确认后可人工发版",
                    wi.getProjectId(), wi.getId());
            log.info("执行链已完成: wi={} deployment={}", wi.getId(), d.getId());
        } catch (Exception e) {
            log.warn("执行链完成通知失败(不阻塞): deployment={} err={}", event.deploymentId(), e.getMessage());
        }
    }

    // ---------------- 内部 ----------------

    /** 触发自动构建：未配构建步骤/已有构建记录则跳过；并发满跳过等补扫。 */
    private void tryTriggerBuild(String projectId, String workItemId, Long seq) {
        if (buildStepRepo.findByProjectIdOrderBySortOrderAsc(projectId).isEmpty()) {
            log.info("项目未配置构建步骤，跳过自动构建: project={} wi={}", projectId, workItemId);
            return;
        }
        if (!buildRepo.findByWorkItemIdOrderByCreatedAtDesc(workItemId).isEmpty()) {
            return; // 幂等：已有构建记录（含进行中），不重复触发
        }
        try {
            BuildView build = buildService.trigger(projectId,
                    new TriggerRequest(null, null, null, null, workItemId));
            log.info("WI 已自动触发构建: wi={} build={}", workItemId, build.id());
        } catch (DevMindException e) {
            if (ErrorCode.CONFLICT.equals(e.getErrorCode())) {
                log.info("构建并发已满，跳过本轮（下次 build.completed 补扫）: wi={}", workItemId);
                return;
            }
            log.warn("自动构建触发失败(跳过): WI-{} err={}", seq, e.getMessage());
        }
    }

    /** 构建成功 → 自动部署 TEST 环境；前置不齐降级为人工。 */
    private void autoDeployTestEnv(String projectId, String workItemId, Long buildId) {
        WorkItemEntity wi = workItemService.requireById(workItemId);
        BuildEntity build = buildRepo.findById(buildId).orElse(null);
        if (build == null || build.getArtifactRef() == null || build.getArtifactRef().isBlank()) {
            degrade(projectId, wi, "构建 #" + buildId + " 未登记产物（artifact），无法自动部署");
            return;
        }
        EnvironmentEntity testEnv = environmentRepo.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(e -> EnvironmentEntity.TEST.equals(e.getName()))
                .findFirst().orElse(null);
        if (testEnv == null) {
            degrade(projectId, wi, "项目未配置 TEST 环境，无法自动部署");
            return;
        }
        DeployConfigEntity config = deployConfigRepo.findByProjectId(projectId);
        if (config == null || config.getStepsJson() == null || config.getStepsJson().isBlank()) {
            degrade(projectId, wi, "项目未配置部署步骤，无法自动部署");
            return;
        }
        try {
            DeploymentView view = deploymentService.create(new CreateDeploymentRequest(
                    projectId, null, testEnv.getId(), buildId, workItemId, null, false, false, null));
            deploymentService.execute(view.id());
            log.info("已自动部署测试环境: wi={} deployment={} env={}", workItemId, view.id(), testEnv.getName());
        } catch (DevMindException e) {
            if (ErrorCode.CONFLICT.equals(e.getErrorCode())) {
                log.info("同构建已有部署记录（幂等跳过）: build={}", buildId);
                return;
            }
            degrade(projectId, wi, "自动部署创建失败：" + e.getMessage());
        }
    }

    /** FR-03：补扫项目内 DONE 且无构建记录的代码类 WI（构建并发释放后的重试）。 */
    private void retryPendingBuilds(String projectId) {
        List<WorkItemView> items = workItemRepo.findByProjectIdOrderBySeqDesc(projectId).stream()
                .map(this::toView).toList();
        Set<String> built = new HashSet<>();
        buildRepo.findByProjectIdOrderByCreatedAtDesc(projectId)
                .forEach(b -> { if (b.getWorkItemId() != null) built.add(b.getWorkItemId()); });
        for (WorkItemView w : PipelineEligibility.pendingBuilds(items, built)) {
            tryTriggerBuild(projectId, w.id(), w.seq());
        }
    }

    private void degrade(String projectId, WorkItemEntity wi, String reason) {
        log.info("执行链降级: wi={} reason={}", wi.getId(), reason);
        notify(NotificationLevel.P1, "pipeline.degraded",
                "WI-" + wi.getSeq() + " 构建完成，请人工部署验证",
                reason + "；可前往项目「部署」Tab 手工创建部署",
                projectId, wi.getId());
    }

    private void notify(NotificationLevel level, String type, String title, String body,
                        String projectId, String workItemId) {
        try {
            notificationService.emit(new NotificationDraft(level, type, title, body,
                    "WORK_ITEM", workItemId, List.of()));
        } catch (Exception e) {
            log.warn("执行链通知发送失败: {}", e.getMessage());
        }
    }

    private WorkItemView toView(WorkItemEntity e) {
        return new WorkItemView(e.getId(), e.getProjectId(), e.getRequirementId(), e.getDesignId(),
                e.getSeq(), "WI-" + e.getSeq(), e.getType(), e.getTitle(), e.getSpec(), e.getStatus(),
                e.getOwnerId(), e.getBranchSlug(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
