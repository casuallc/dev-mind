package com.devmind.flow;

import com.devmind.auth.IdentityService;
import com.devmind.artifact.ArtifactService;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.docs.dto.SaveVersionRequest;
import com.devmind.flow.dto.SplitDraftItem;
import com.devmind.notification.dto.ActionDef;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.DesignService;
import com.devmind.project.RelationService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.DesignRequest;
import com.devmind.project.dto.DesignView;
import com.devmind.project.dto.RelationRequest;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.DesignEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.service.SessionManagerService;
import com.devmind.session.service.SessionOutputService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 需求流程引擎（CAP-14/CAP-37/CAP-38）：需求主流程的半自动推进——每阶段一个流程动作（起会话/生成），
 * 阶段可跳过（skipStage），方案产出后自动拆分并直接固化为正式工作单元（无人工确认草稿环节）。
 * 只做粘合与门禁校验，不做自动调度（属 CAP-15 Orchestrator）。
 *
 * <p>会话归属约定：分析/拆分会话直挂 requirementId（taskSpec 首行 [flow:*] 标记区分）；
 * 方案会话挂 DESIGN 型 Work Item；执行会话挂普通 Work Item。</p>
 *
 * <p>CAP-37：产出读取走 session_outputs（runner 退出前回传），不再依赖 worktree 路径
 * （CAP-34 后服务端零执行，worktree_path 恒 null）；上游产出（分析文档/已确认方案）
 * 注入下游会话 spec，阶段间上下文串联。</p>
 */
@Service
public class RequirementFlowService {

    private static final Logger log = LoggerFactory.getLogger(RequirementFlowService.class);

    private final IdentityService identityService;
    private final RequirementService requirementService;
    private final WorkItemService workItemService;
    private final DesignService designService;
    private final RelationService relationService;
    private final SessionManagerService sessionManager;
    private final SessionRepository sessionRepo;
    private final SessionOutputService sessionOutputService;
    private final DocumentService documentService;
    private final ArtifactService artifactService;
    private final NotificationService notificationService;
    private final DomainEventPublisher eventPublisher;
    private final ObjectMapper mapper;

    public RequirementFlowService(RequirementService requirementService,
                                  WorkItemService workItemService,
                                  DesignService designService,
                                  RelationService relationService,
                                  SessionManagerService sessionManager,
                                  SessionRepository sessionRepo,
                                  SessionOutputService sessionOutputService,
                                  DocumentService documentService,
                                  ArtifactService artifactService,
                                  NotificationService notificationService,
                                  DomainEventPublisher eventPublisher,
                                  ObjectMapper mapper,
                           IdentityService identityService) {
        this.identityService = identityService;
        this.requirementService = requirementService;
        this.workItemService = workItemService;
        this.designService = designService;
        this.relationService = relationService;
        this.sessionManager = sessionManager;
        this.sessionRepo = sessionRepo;
        this.sessionOutputService = sessionOutputService;
        this.documentService = documentService;
        this.artifactService = artifactService;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.mapper = mapper;
    }

    // ---------------- 阶段动作 ----------------

    /** FR-01 开始/重新分析：DRAFT/ANALYZING 可用；起分析型会话并把需求推进到 ANALYZING。 */
    public SessionView startAnalysis(String projectId, String requirementId) {
        RequirementEntity req = requirementService.requireEntity(projectId, requirementId);
        if (!RequirementEntity.STATUS_DRAFT.equals(req.getStatus())
                && !RequirementEntity.STATUS_ANALYZING.equals(req.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "当前状态 " + req.getStatus() + " 不能发起分析（仅 DRAFT/ANALYZING 可分析）");
        }
        SessionView session = sessionManager.create(new CreateSessionRequest(
                null, projectId, null, requirementId,
                FlowOutputContract.analysisSpec(req), null, null, null, null));
        if (RequirementEntity.STATUS_DRAFT.equals(req.getStatus())) {
            requirementService.updateStatus(projectId, requirementId, RequirementEntity.STATUS_ANALYZING);
        }
        log.info("需求分析会话已启动: req={} session={}", requirementId, session.id());
        return session;
    }

    /** FR-02 生成方案：创建 DESIGN 型 Work Item 并起会话（spec=方案输出契约 + 最近一次分析结论）。 */
    public SessionView startDesign(String projectId, String requirementId) {
        RequirementEntity req = requirementService.requireEntity(projectId, requirementId);
        if (!RequirementEntity.STATUS_ANALYZING.equals(req.getStatus())
                && !RequirementEntity.STATUS_DESIGNING.equals(req.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "当前状态 " + req.getStatus() + " 不能发起方案设计（需先完成分析）");
        }
        boolean activeDesign = workItemService.list(projectId, requirementId).stream()
                .anyMatch(w -> WorkItemEntity.TYPE_DESIGN.equals(w.type()) && !isTerminal(w.status()));
        if (activeDesign) {
            throw new DevMindException(ErrorCode.CONFLICT, "已有进行中的方案工作单元，请先完成或取消");
        }
        WorkItemView wi = workItemService.create(projectId, requirementId, new WorkItemRequest(
                WorkItemEntity.TYPE_DESIGN, "方案设计 - " + req.getTitle(),
                FlowOutputContract.designSpec(req, latestAnalysisContent(requirementId)), null, null, null));
        return startWorkItemSession(projectId, wi.id());
    }

    /** FR-03 AI 拆分（手动路径，跳过方案后可用）：校验前置（无进行中 WI）后起拆分会话。
     *  CAP-38：方案确认（CONFIRMED）不再门控——有 CONFIRMED 用其内容，否则用最新方案文档，都没有注入分析。 */
    public SessionView startSplit(String projectId, String requirementId) {
        RequirementEntity req = requirementService.requireEntity(projectId, requirementId);
        if (!RequirementEntity.STATUS_ANALYZING.equals(req.getStatus())
                && !RequirementEntity.STATUS_DESIGNING.equals(req.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "当前状态 " + req.getStatus() + " 不能发起拆分（需处于 ANALYZING/DESIGNING）");
        }
        List<WorkItemView> items = workItemService.list(projectId, requirementId);
        if (hasActiveExecution(items)) {
            throw new DevMindException(ErrorCode.CONFLICT, "已有进行中的工作单元，不能重复拆分");
        }
        return launchSplit(req, items, resolveDesignContent(projectId, requirementId));
    }

    /** CAP-38 FR-01 阶段跳过：幂等置标记；跳过分析且需求 DRAFT → 推进 ANALYZING（下游门禁自然放行）。 */
    public void skipStage(String projectId, String requirementId, String stage) {
        RequirementEntity req = requirementService.requireEntity(projectId, requirementId);
        if ("analysis".equals(stage)) {
            if (documentService.findLatestByKind(requirementId, "analysis").isPresent()) {
                throw new DevMindException(ErrorCode.CONFLICT, "分析已完成，不能跳过");
            }
            if (!req.getAnalysisSkipped()) {
                req.setAnalysisSkipped(true);
                requirementService.saveStageFlags(req);
            }
            if (RequirementEntity.STATUS_DRAFT.equals(req.getStatus())) {
                requirementService.updateStatus(projectId, requirementId, RequirementEntity.STATUS_ANALYZING);
            }
        } else if ("design".equals(stage)) {
            boolean hasDesign = designService.list(projectId, requirementId).stream()
                    .anyMatch(d -> !DesignEntity.STATUS_DISCARDED.equals(d.status()));
            if (hasDesign) {
                throw new DevMindException(ErrorCode.CONFLICT, "方案已生成，不能跳过");
            }
            if (!req.getDesignSkipped()) {
                req.setDesignSkipped(true);
                requirementService.saveStageFlags(req);
            }
        } else {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "stage 仅支持 analysis/design");
        }
        log.info("流程阶段已跳过: req={} stage={}", requirementId, stage);
    }

    /** 方案内容解析：已确认方案优先，其次最新方案文档；皆无返回 null。 */
    private String resolveDesignContent(String projectId, String requirementId) {
        List<DesignView> designs = designService.list(projectId, requirementId);
        Long docId = designs.stream()
                .filter(d -> DesignEntity.STATUS_CONFIRMED.equals(d.status()))
                .findFirst().map(DesignView::docId).orElse(null);
        try {
            if (docId != null) {
                return documentService.get(docId, null).contentMd();
            }
            return documentService.findLatestByKind(requirementId, "design")
                    .map(DocDetail::contentMd).orElse(null);
        } catch (Exception e) {
            log.warn("读取方案文档失败(拆分继续,不含方案内容): req={} err={}", requirementId, e.getMessage());
            return null;
        }
    }

    /** 起拆分会话（手动/自动共用）：DESIGN 型 WI 置 DONE 让 rollup 离开 DESIGNING，注入方案+分析起会话。 */
    private SessionView launchSplit(RequirementEntity req, List<WorkItemView> items, String designContent) {
        for (WorkItemView w : items) {
            if (WorkItemEntity.TYPE_DESIGN.equals(w.type()) && !isTerminal(w.status())) {
                workItemService.updateStatus(req.getProjectId(), req.getId(), w.id(), WorkItemEntity.STATUS_DONE);
            }
        }
        SessionView session = sessionManager.create(new CreateSessionRequest(
                null, req.getProjectId(), null, req.getId(),
                FlowOutputContract.splitSpec(req, designContent, latestAnalysisContent(req.getId())),
                null, null, null, null));
        log.info("需求拆分会话已启动: req={} session={}", req.getId(), session.id());
        return session;
    }

    private boolean hasActiveExecution(List<WorkItemView> items) {
        return items.stream()
                .anyMatch(w -> !WorkItemEntity.TYPE_DESIGN.equals(w.type()) && !isTerminal(w.status()));
    }

    /** FR-04 工作单元起会话：WI.spec 自动带入 taskSpec；TODO 的 WI 推进为 IN_PROGRESS。 */
    public SessionView startWorkItemSession(String projectId, String workItemId) {
        WorkItemEntity wi = workItemService.requireEntity(projectId, workItemId);
        if (isTerminal(wi.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "工作单元已 " + wi.getStatus() + "，不能起会话");
        }
        if (wi.getSpec() == null || wi.getSpec().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "工作单元缺少 spec（执行说明），请先补充再派发");
        }
        SessionView session = sessionManager.create(new CreateSessionRequest(
                null, projectId, wi.getId(), wi.getRequirementId(), wi.getSpec(), null, null, null, null));
        if (WorkItemEntity.STATUS_TODO.equals(wi.getStatus())) {
            workItemService.updateStatus(projectId, wi.getRequirementId(), wi.getId(),
                    WorkItemEntity.STATUS_IN_PROGRESS);
        }
        log.info("工作单元会话已启动: wi={} session={}", workItemId, session.id());
        return session;
    }

    // ---------------- 拆分固化（CAP-38 FR-03：wi-plan.json 产出直接固化，无人工确认环节） ----------------

    /** 拆分会话 DONE → 解析 wi-plan.json 直接固化为正式 WI（建 depends_on 边 + 发 flow.split.confirmed，编排器自动派发）；
     *  产出缺失/解析为空/校验失败 → flow.split.missing 降级通知人工建。 */
    private void handleSplitOutput(SessionEntity session) {
        RequirementEntity req = requirementService.requireById(session.getRequirementId());
        List<SplitDraftItem> items = parseWiPlan(readOutput(session, FlowOutputContract.WI_PLAN_FILE));
        String invalid = null;
        if (items.isEmpty()) {
            invalid = "未找到有效产出 " + FlowOutputContract.OUTPUT_DIR + "/" + FlowOutputContract.WI_PLAN_FILE;
        } else {
            try {
                SplitPlanValidator.validate(items);
            } catch (DevMindException e) {
                invalid = "拆分清单校验失败（" + e.getMessage() + "）";
            }
        }
        if (invalid != null) {
            notify(NotificationLevel.P1, "flow.split.missing",
                    "REQ-" + req.getSeq() + " 拆分未能自动固化",
                    invalid + "，请查看会话输出后在工作单元 Tab 手工新建或重新拆分",
                    req.getProjectId(), req.getId());
            return;
        }
        int count = applySplitPlan(req, items);
        notify(NotificationLevel.P1, "flow.split.done",
                "REQ-" + req.getSeq() + " 已自动创建 " + count + " 个工作单元",
                "AI 拆分已固化，首批无依赖工作单元将自动派发会话；请前往「工作单元」Tab 查看",
                req.getProjectId(), req.getId());
    }

    /** 固化拆分清单：批量建 Work Item（触发既有 rollup）+ 按 dependsOn 下标建 depends_on 边，返回创建数。 */
    private int applySplitPlan(RequirementEntity req, List<SplitDraftItem> items) {
        String projectId = req.getProjectId();
        String requirementId = req.getId();
        String designId = designService.list(projectId, requirementId).stream()
                .filter(d -> DesignEntity.STATUS_CONFIRMED.equals(d.status()))
                .findFirst().map(DesignView::id).orElse(null);
        List<String> wiIds = new ArrayList<>();
        for (SplitDraftItem it : items) {
            WorkItemView v = workItemService.create(projectId, requirementId, new WorkItemRequest(
                    it.type(), it.title(), it.spec(), designId, null, null));
            wiIds.add(v.id());
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).dependsOn() == null) {
                continue;
            }
            for (int dep : items.get(i).dependsOn()) {
                relationService.create(projectId, new RelationRequest(
                        "work_item", wiIds.get(i), "work_item", wiIds.get(dep), "depends_on"));
            }
        }
        log.info("拆分已自动固化: req={} workItems={}", requirementId, wiIds.size());
        // CAP-15：发布固化事件，编排器订阅后对无依赖的首批 WI 自动派发会话（不转通知，编排器自发派发通知）
        eventPublisher.publish(SimpleDomainEvent.of("flow.split.confirmed", projectId, null,
                identityService.currentActor(), "需求拆分已固化，工作单元 " + wiIds.size() + " 个",
                "REQUIREMENT", requirementId, null));
        return wiIds.size();
    }

    // ---------------- 会话完成分流 ----------------

    /**
     * 分流专用单线程执行器：agent WS 每连接消息串行派发——分流链路里的 launch 等待 ack
     * （autoSplit、拆分固化后编排器首批派发都在会话完成事件链里起新会话），
     * 若同步跑在 WS 读线程上，ack 帧进不来必然 15s 超时。单线程保序且离开 WS 线程。
     */
    private final ExecutorService flowExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "requirement-flow");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        flowExecutor.shutdown();
    }

    /** FR-05：消费 session.completed，按会话归属登记产物/文档并通知人确认。失败会话已由统一监听器发 P0。 */
    @EventListener
    public void onSessionCompleted(SimpleDomainEvent event) {
        if (!"session.completed".equals(event.type()) || !Boolean.TRUE.equals(event.success())) {
            return;
        }
        flowExecutor.submit(() -> handleCompleted(event.entityId()));
    }

    /** 完成事件分流本体（包可见便于单测同步驱动；生产路径恒走 flowExecutor）。 */
    void handleCompleted(String sessionId) {
        sessionRepo.findById(sessionId).ifPresent(session -> {
            try {
                dispatch(session);
            } catch (Exception e) {
                log.warn("流程引擎处理会话完成事件失败(不阻塞): session={} err={}", session.getId(), e.getMessage());
            }
        });
    }

    private void dispatch(SessionEntity session) {
        if (session.getWorkItemId() != null && !session.getWorkItemId().isBlank()) {
            WorkItemEntity wi = workItemService.requireById(session.getWorkItemId());
            if (WorkItemEntity.TYPE_DESIGN.equals(wi.getType())) {
                handleDesignOutput(session, wi);
            }
            return;
        }
        if (session.getRequirementId() == null || session.getTaskSpec() == null) {
            return;
        }
        if (session.getTaskSpec().startsWith(FlowOutputContract.MARKER_ANALYZE)) {
            handleAnalysisOutput(session);
        } else if (session.getTaskSpec().startsWith(FlowOutputContract.MARKER_SPLIT)) {
            handleSplitOutput(session);
        }
    }

    /** 分析会话 DONE → 读 analysis.md 落成/更新需求分析文档（kind=analysis），登记 ANALYSIS 产物（ref=docId），通知"分析就绪待确认"。 */
    private void handleAnalysisOutput(SessionEntity session) {
        RequirementEntity req = requirementService.requireById(session.getRequirementId());
        String content = readOutput(session, FlowOutputContract.ANALYSIS_FILE);
        if (content == null) {
            notify(NotificationLevel.P1, "flow.analysis.missing",
                    "REQ-" + req.getSeq() + " 分析会话已完成",
                    "未找到约定产出 " + FlowOutputContract.OUTPUT_DIR + "/" + FlowOutputContract.ANALYSIS_FILE
                            + "，请查看会话输出并人工整理结论",
                    req.getProjectId(), req.getId());
            return;
        }
        // 重新分析不新建第二份文档：走既有文档版本化更新
        DocDetail doc = documentService.findLatestByKind(req.getId(), "analysis")
                .map(existing -> documentService.saveVersion(existing.id(),
                        new SaveVersionRequest(content, "AI 重新分析")))
                .orElseGet(() -> documentService.create(new DocRequest(
                        "analysis", req.getId(), null, session.getProjectId(),
                        "分析 - " + req.getTitle(), null, null, content)));
        artifactService.registerInfo(session.getProjectId(), req.getId(), null,
                com.devmind.artifact.model.ArtifactEntity.TYPE_ANALYSIS,
                "REQ-" + req.getSeq() + " 需求分析 v" + doc.versionNo(),
                String.valueOf(doc.id()), ArtifactService.PRODUCER_SESSION);
        notify(NotificationLevel.P1, "flow.analysis.ready",
                "REQ-" + req.getSeq() + " 分析就绪",
                "分析文档已生成（v" + doc.versionNo() + "），请前往「需求分析」Tab 查阅后决定生成方案或跳过",
                req.getProjectId(), req.getId());
    }

    /** DESIGN 型 WI 会话 DONE → 读 design.md 登记方案文档 + Design(DRAFT) + DOC 产物，通知并自动拆分（autoSplit）。 */
    private void handleDesignOutput(SessionEntity session, WorkItemEntity wi) {
        RequirementEntity req = requirementService.requireById(session.getRequirementId());
        String content = readOutput(session, FlowOutputContract.DESIGN_FILE);
        if (content == null) {
            notify(NotificationLevel.P1, "flow.design.missing",
                    "REQ-" + req.getSeq() + " 方案会话已完成",
                    "未找到约定产出 " + FlowOutputContract.OUTPUT_DIR + "/" + FlowOutputContract.DESIGN_FILE
                            + "，请查看会话输出并人工登记方案",
                    req.getProjectId(), req.getId());
            return;
        }
        DocDetail doc = documentService.create(new DocRequest(
                "design", req.getId(), wi.getId(), session.getProjectId(),
                "方案 - " + req.getTitle(), null, null, content));
        DesignView design = designService.create(session.getProjectId(), req.getId(),
                new DesignRequest(doc.id()));
        artifactService.registerInfo(session.getProjectId(), req.getId(), wi.getId(),
                com.devmind.artifact.model.ArtifactEntity.TYPE_DOC,
                "方案 v" + design.version(), String.valueOf(doc.id()), ArtifactService.PRODUCER_SESSION);
        notify(NotificationLevel.P1, "flow.design.ready",
                "REQ-" + req.getSeq() + " 方案 v" + design.version() + " 已生成",
                "AI 已生成方案文档，若无进行中工作单元将自动拆分；请前往「方案设计」Tab 查看",
                req.getProjectId(), req.getId());
        autoSplit(req, content);
    }

    /** CAP-38 FR-03 方案产出后自动拆分：无进行中执行 WI 才自动起拆分会话（不等方案 CONFIRMED）；
     *  门禁不满足/启动失败仅通知降级，不阻塞方案登记。 */
    private void autoSplit(RequirementEntity req, String designContent) {
        try {
            List<WorkItemView> items = workItemService.list(req.getProjectId(), req.getId());
            if (hasActiveExecution(items)) {
                notify(NotificationLevel.P1, "flow.split.deferred",
                        "REQ-" + req.getSeq() + " 已跳过自动拆分",
                        "存在进行中的工作单元，未自动拆分；可在其完成后于「工作单元」Tab 手动 AI 拆分",
                        req.getProjectId(), req.getId());
                return;
            }
            launchSplit(req, items, designContent);
        } catch (Exception e) {
            log.warn("自动拆分失败(降级人工触发): req={} err={}", req.getId(), e.getMessage());
            notify(NotificationLevel.P1, "flow.split.deferred",
                    "REQ-" + req.getSeq() + " 自动拆分未启动",
                    "原因：" + e.getMessage() + "；可在「工作单元」Tab 手动 AI 拆分",
                    req.getProjectId(), req.getId());
        }
    }

    // ---------------- 内部 ----------------

    /** 读会话产出（runner 回传的 session_outputs）；不存在返回 null。 */
    private String readOutput(SessionEntity session, String fileName) {
        return sessionOutputService.findContent(session.getId(), fileName).orElse(null);
    }

    /** 最近一次分析文档内容；无文档/读取失败返回 null（spec 注入降级为省略该节，不阻断流程）。 */
    private String latestAnalysisContent(String requirementId) {
        try {
            return documentService.findLatestByKind(requirementId, "analysis")
                    .map(DocDetail::contentMd).orElse(null);
        } catch (Exception e) {
            log.warn("读取分析文档失败(流程继续,不含分析内容): req={} err={}", requirementId, e.getMessage());
            return null;
        }
    }

    /** 解析 wi-plan.json（容忍 ```json 代码围栏包裹）；解析失败返回空列表。 */
    private List<SplitDraftItem> parseWiPlan(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            json = json.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").trim();
            }
            JsonNode arr = mapper.readTree(json);
            if (!arr.isArray()) {
                return List.of();
            }
            List<SplitDraftItem> items = new ArrayList<>();
            for (JsonNode n : arr) {
                List<Integer> deps = new ArrayList<>();
                JsonNode dn = n.get("dependsOn");
                if (dn != null && dn.isArray()) {
                    for (JsonNode d : dn) {
                        deps.add(d.asInt());
                    }
                }
                items.add(new SplitDraftItem(
                        n.hasNonNull("type") ? n.get("type").asText() : WorkItemEntity.TYPE_DEVELOPMENT,
                        n.hasNonNull("title") ? n.get("title").asText() : "",
                        n.hasNonNull("spec") ? n.get("spec").asText() : "",
                        deps));
            }
            return items;
        } catch (Exception e) {
            log.warn("解析拆分草稿失败: err={}", e.getMessage());
            return List.of();
        }
    }

    private void notify(NotificationLevel level, String type, String title, String body,
                        String projectId, String requirementId) {
        notificationService.emit(new NotificationDraft(level, type, title, body,
                "REQUIREMENT", requirementId, projectId, List.of(new ActionDef("view", "查看需求"))));
    }

    private boolean isTerminal(String status) {
        return WorkItemEntity.STATUS_DONE.equals(status) || WorkItemEntity.STATUS_CANCELLED.equals(status);
    }
}
