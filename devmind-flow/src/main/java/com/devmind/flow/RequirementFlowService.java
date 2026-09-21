package com.devmind.flow;

import com.devmind.auth.IdentityService;
import com.devmind.artifact.ArtifactService;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.docs.dto.SaveVersionRequest;
import com.devmind.flow.dto.PublishOutputRequest;
import com.devmind.flow.dto.PublishOutputResult;
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
import com.devmind.project.dto.RelationView;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.DesignEntity;
import com.devmind.project.model.RelationEntity;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 需求流程引擎（CAP-14/CAP-37/CAP-38/CAP-52）：需求主流程的半自动推进。
 *
 * <p>CAP-52 起正路只有两步：**开启 AI 规划**（{@link #startPlan}，一个会话产出分析+方案+工作单元，
 * 产出齐备后自动起开发会话）与**需求级开发**（{@link #startDev}，整份清单交给一个会话）。
 * 逐阶段起会话（分析/方案/拆分/跳过）的入口已删除——它们让每个阶段都重起进程、重注入上下文、
 * 由服务端把上游产出当字符串搬运，正是 CAP-52 要消灭的 token 与请求开销。</p>
 *
 * <p>会话归属约定：规划/开发会话直挂 requirementId（taskSpec 首行 [flow:*] 标记区分）；
 * 存量 DESIGN 型 Work Item 会话仍走 {@code handleDesignOutput} 分支（人工逃生通道）。</p>
 *
 * <p>CAP-37：产出读取走 session_outputs（runner 退出前回传），不再依赖 worktree 路径
 * （CAP-34 后服务端零执行，worktree_path 恒 null）。</p>
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

    // ---------------- CAP-52：一次点击的规划会话 + 需求级开发会话 ----------------

    /**
     * CAP-52 FR-01「开启 AI 规划」：**一个会话**产出分析 + 方案 + 工作单元三个文件。
     *
     * <p>取代 CAP-14 的 startAnalysis/startDesign/startSplit 三入口（FR-07 已删端点，前端只剩
     * 这一个按钮）。存量「跳过分析/方案」标记降级为**产出范围**：跳过的文件不要求产出，
     * 但不再是门禁——只剩一个动作，没有下游要解锁。</p>
     */
    public SessionView startPlan(String projectId, String requirementId) {
        RequirementEntity req = requirementService.requireEntity(projectId, requirementId);
        requireNotTerminal(req, "发起规划");
        requireNoActiveSession(requirementId, "发起规划");
        SessionView session = sessionManager.create(new CreateSessionRequest(
                null, projectId, null, requirementId,
                FlowOutputContract.planSpec(req, req.getAnalysisSkipped(), req.getDesignSkipped()),
                null, null, null, null));
        if (RequirementEntity.STATUS_DRAFT.equals(req.getStatus())) {
            requirementService.updateStatus(projectId, requirementId, RequirementEntity.STATUS_ANALYZING);
        }
        log.info("需求规划会话已启动: req={} session={}", requirementId, session.id());
        return session;
    }

    /**
     * CAP-52 FR-04「重新开发」：按已固化的工作单元清单重起需求级开发会话。
     * 规划成功后由 {@link #startDevBestEffort} 自动起一次；本方法供人工在做完一轮改动后再来一轮。
     */
    public SessionView startDev(String projectId, String requirementId) {
        RequirementEntity req = requirementService.requireEntity(projectId, requirementId);
        requireNotTerminal(req, "发起开发");
        requireNoActiveSession(requirementId, "发起开发");
        List<WorkItemView> items = workItemService.list(projectId, requirementId);
        if (items.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT, "该需求还没有工作单元，请先「开启 AI 规划」");
        }
        return launchDev(req, items);
    }

    /**
     * 起需求级开发会话：清单整份交给一个会话，全部 TODO 工作单元置 IN_PROGRESS。
     *
     * <p>为什么一个需求只起一个：逐 WI 派发会让每个 WI 都重新起进程、重新理解需求、重新注入
     * 上下文，请求数与 token 随 WI 数线性增长；配合 CAP-51 的需求工作树，一个会话连续做完
     * 的改动天然累积在同一条需求分支上。</p>
     */
    private SessionView launchDev(RequirementEntity req, List<WorkItemView> items) {
        SessionView session = sessionManager.create(new CreateSessionRequest(
                null, req.getProjectId(), null, req.getId(),
                FlowOutputContract.devSpec(req, toDevItems(req, items)), null, null, null, null));
        for (WorkItemView w : items) {
            if (!WorkItemEntity.STATUS_TODO.equals(w.status())) {
                continue;
            }
            try {
                workItemService.updateStatus(req.getProjectId(), req.getId(), w.id(),
                        WorkItemEntity.STATUS_IN_PROGRESS);
            } catch (Exception e) {
                log.warn("工作单元置 IN_PROGRESS 失败(不阻塞开发会话): wi={} err={}", w.id(), e.getMessage());
            }
        }
        log.info("需求开发会话已启动: req={} session={} workItems={}",
                req.getId(), session.id(), items.size());
        return session;
    }

    /** 规划/拆分固化后自动起开发会话：失败只降级通知（人可在工作单元行内起会话或点「重新开发」）。 */
    private void startDevBestEffort(RequirementEntity req) {
        try {
            List<WorkItemView> items = workItemService.list(req.getProjectId(), req.getId());
            if (items.isEmpty()) {
                return;
            }
            SessionView session = launchDev(req, items);
            notify(NotificationLevel.P1, "flow.dispatched",
                    "REQ-" + req.getSeq() + " 开发会话已启动",
                    "工作单元清单（" + items.size() + " 条）已交给开发会话，将一次做完整个需求",
                    req.getProjectId(), req.getId());
            log.info("需求开发会话自动启动: req={} session={}", req.getId(), session.id());
        } catch (Exception e) {
            log.warn("自动起开发会话失败(降级人工): req={} err={}", req.getId(), e.getMessage());
            notify(NotificationLevel.P1, "flow.dev.deferred",
                    "REQ-" + req.getSeq() + " 开发会话未自动启动",
                    "原因：" + e.getMessage() + "；可在「工作单元」Tab 行内起会话，或重新规划",
                    req.getProjectId(), req.getId());
        }
    }

    /** 清单渲染：序号 = 固化顺序；依赖写成「先完成 #n」的人话（开发会话看不到 relations 表）。 */
    private List<FlowOutputContract.DevItem> toDevItems(RequirementEntity req, List<WorkItemView> items) {
        Map<String, Integer> seqOf = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            seqOf.put(items.get(i).id(), i + 1);
        }
        Map<String, List<String>> depsOf = new HashMap<>();
        try {
            for (RelationView e : relationService.list(req.getProjectId(), null, null)) {
                if (!"work_item".equals(e.fromType())
                        || !RelationEntity.TYPE_DEPENDS_ON.equals(e.relationType())) {
                    continue;
                }
                Integer dep = seqOf.get(e.toId());
                if (dep != null) {
                    depsOf.computeIfAbsent(e.fromId(), k -> new ArrayList<>()).add("#" + dep);
                }
            }
        } catch (Exception e) {
            log.warn("读取工作单元依赖失败(清单不含依赖提示): req={} err={}", req.getId(), e.getMessage());
        }
        List<FlowOutputContract.DevItem> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            WorkItemView w = items.get(i);
            out.add(new FlowOutputContract.DevItem(i + 1, w.type(), w.title(), w.spec(),
                    depsOf.getOrDefault(w.id(), List.of())));
        }
        return out;
    }

    /** 需求终态（DONE/CANCELLED）不可再起流程会话。 */
    private void requireNotTerminal(RequirementEntity req, String action) {
        if (RequirementEntity.STATUS_DONE.equals(req.getStatus())
                || RequirementEntity.STATUS_CANCELLED.equals(req.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "需求已 " + req.getStatus() + "，不能" + action);
        }
    }

    /**
     * 同需求互斥预检：该需求已有进行中会话时友好报错（比 runner 侧「工作区被占用」可读得多）。
     * CAP-51 的需求级占用预检是同一语义的另一道防线，两者都保留。
     */
    private void requireNoActiveSession(String requirementId, String action) {
        for (SessionEntity s : sessionRepo.findByRequirementIdOrderByCreatedAtDesc(requirementId)) {
            if (isActiveStatus(s.getStatus())) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "该需求已有进行中的会话 " + s.getId() + "，请等它结束后再" + action);
            }
        }
    }

    private static boolean isActiveStatus(String status) {
        try {
            return SessionState.valueOf(status).isActive();
        } catch (Exception e) {
            return false; // 状态缺失/未知：不当作进行中，避免把需求锁死
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

    /** 拆分会话 DONE → 解析 wi-plan.json 直接固化为正式 WI（建 depends_on 边 + 发 flow.split.confirmed）。
     *  产出缺失/解析为空/校验失败 → flow.split.missing 降级通知人工建。
     *  CAP-52：固化成功后起**一个**需求级开发会话（不再逐 WI 派发）。 */
    private void handleSplitOutput(SessionEntity session) {
        RequirementEntity req = requirementService.requireById(session.getRequirementId());
        List<SplitDraftItem> items = parseWiPlan(readOutput(session, FlowOutputContract.WI_PLAN_FILE));
        String invalid = validatePlan(items);
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
                "AI 拆分已固化，正在启动需求级开发会话；请前往「工作单元」Tab 查看",
                req.getProjectId(), req.getId());
        startDevBestEffort(req);
    }

    /** 清单校验：空清单/校验失败返回原因文案，通过返回 null。 */
    private String validatePlan(List<SplitDraftItem> items) {
        if (items.isEmpty()) {
            return "未找到有效产出 " + FlowOutputContract.OUTPUT_DIR + "/" + FlowOutputContract.WI_PLAN_FILE;
        }
        try {
            SplitPlanValidator.validate(items);
            return null;
        } catch (DevMindException e) {
            return "工作单元清单校验失败（" + e.getMessage() + "）";
        }
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
        if (session.getTaskSpec().startsWith(FlowOutputContract.MARKER_PLAN)) {
            handlePlanOutput(session);
        } else if (session.getTaskSpec().startsWith(FlowOutputContract.MARKER_DEV)) {
            handleDevOutput(session);
        } else if (session.getTaskSpec().startsWith(FlowOutputContract.MARKER_ANALYZE)) {
            // 存量在途会话（升级前起的分析/拆分会话）照旧分流
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
        DocDetail doc = registerAnalysisDocument(req, session, content);
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
        DesignView design = registerDesignDocument(req, session, content, wi.getId());
        notify(NotificationLevel.P1, "flow.design.ready",
                "REQ-" + req.getSeq() + " 方案 v" + design.version() + " 已生成",
                "AI 已生成方案文档，若无进行中工作单元将自动拆分；请前往「方案设计」Tab 查看",
                req.getProjectId(), req.getId());
        autoSplit(req, content);
    }

    /**
     * CAP-52 FR-02 规划会话 DONE：三个文件**独立登记**（缺一个不拖垮其余），三项齐了才起开发会话。
     *
     * <p>与旧「分析/方案/拆分各一个会话」的关键差别：三份产出在同一条对话里产生，服务端不做
     * 上游产出的字符串搬运（那正是 token 浪费的来源）；这里只负责落库与分流。</p>
     */
    private void handlePlanOutput(SessionEntity session) {
        RequirementEntity req = requirementService.requireById(session.getRequirementId());
        List<String> missing = new ArrayList<>();
        // 跳过的阶段不要求产出（存量标记 → 产出范围，不是门禁）
        String analysis = readOutput(session, FlowOutputContract.ANALYSIS_FILE);
        if (analysis == null) {
            if (!req.getAnalysisSkipped()) {
                missing.add(FlowOutputContract.ANALYSIS_FILE);
            }
        } else {
            try {
                registerAnalysisDocument(req, session, analysis);
            } catch (Exception e) {
                log.warn("分析文档登记失败: req={} err={}", req.getId(), e.getMessage());
                missing.add(FlowOutputContract.ANALYSIS_FILE + "（登记失败）");
            }
        }
        String design = readOutput(session, FlowOutputContract.DESIGN_FILE);
        if (design == null) {
            if (!req.getDesignSkipped()) {
                missing.add(FlowOutputContract.DESIGN_FILE);
            }
        } else {
            try {
                registerDesignDocument(req, session, design, null);
            } catch (Exception e) {
                log.warn("方案文档登记失败: req={} err={}", req.getId(), e.getMessage());
                missing.add(FlowOutputContract.DESIGN_FILE + "（登记失败）");
            }
        }
        String planJson = readOutput(session, FlowOutputContract.WI_PLAN_FILE);
        int created = 0;
        if (planJson == null) {
            missing.add(FlowOutputContract.WI_PLAN_FILE);
        } else {
            List<SplitDraftItem> items = parseWiPlan(planJson);
            String invalid = validatePlan(items);
            if (invalid != null) {
                missing.add(FlowOutputContract.WI_PLAN_FILE + "（" + invalid + "）");
            } else {
                created = applySplitPlan(req, items);
            }
        }
        if (missing.isEmpty()) {
            notify(NotificationLevel.P1, "flow.plan.done",
                    "REQ-" + req.getSeq() + " 规划完成",
                    "分析/方案/工作单元（" + created + " 条）已就绪，正在启动开发会话；"
                            + "请前往「工作单元」Tab 查看",
                    req.getProjectId(), req.getId());
            startDevBestEffort(req);
            return;
        }
        notify(NotificationLevel.P1, "flow.plan.partial",
                "REQ-" + req.getSeq() + " 规划产出不完整",
                "未登记：" + String.join("；", missing)
                        + "。可重新规划，或在「工作单元」Tab 手工新建工作单元",
                req.getProjectId(), req.getId());
    }

    /**
     * CAP-52 FR-06 开发会话 DONE → 需求进 ACCEPTANCE 待验收。
     *
     * <p>**不自动把工作单元置 DONE**：CAP-17 执行链按 WI DONE 触发构建，N 条一起自动 DONE 会掀起
     * N 次构建；且「AI 产出、人验收」是既有原则。人可在工作单元 Tab 批量确认。</p>
     */
    private void handleDevOutput(SessionEntity session) {
        RequirementEntity req = requirementService.requireById(session.getRequirementId());
        String summary = readOutput(session, FlowOutputContract.DEV_SUMMARY_FILE);
        if (!RequirementEntity.STATUS_DONE.equals(req.getStatus())
                && !RequirementEntity.STATUS_CANCELLED.equals(req.getStatus())
                && !RequirementEntity.STATUS_ACCEPTANCE.equals(req.getStatus())) {
            try {
                requirementService.updateStatus(req.getProjectId(), req.getId(),
                        RequirementEntity.STATUS_ACCEPTANCE);
            } catch (Exception e) {
                log.warn("需求置 ACCEPTANCE 失败(不阻塞通知): req={} err={}", req.getId(), e.getMessage());
            }
        }
        String body = summary == null
                ? "开发会话已完成并提交改动，但未找到产出 " + FlowOutputContract.OUTPUT_DIR + "/"
                        + FlowOutputContract.DEV_SUMMARY_FILE + "；请到会话详情核对改动后再验收。"
                : "开发会话已完成并提交改动。摘要：" + preview(summary, 400) + "（完整内容见会话产出）";
        notify(NotificationLevel.P1, "flow.dev.done",
                "REQ-" + req.getSeq() + " 开发完成待验收",
                body, req.getProjectId(), req.getId());
    }

    /** 分析文档登记：已存在则版本化更新（重新分析不新建第二份），登记 ANALYSIS 产物。 */
    private DocDetail registerAnalysisDocument(RequirementEntity req, SessionEntity session, String content) {
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
        return doc;
    }

    /** 方案文档登记：新建 design 文档 + Design(DRAFT) + DOC 产物（workItemId 可为 null = 规划会话产出）。 */
    private DesignView registerDesignDocument(RequirementEntity req, SessionEntity session, String content,
                                              String workItemId) {
        DocDetail doc = documentService.create(new DocRequest(
                "design", req.getId(), workItemId, session.getProjectId(),
                "方案 - " + req.getTitle(), null, null, content));
        DesignView design = designService.create(session.getProjectId(), req.getId(),
                new DesignRequest(doc.id()));
        artifactService.registerInfo(session.getProjectId(), req.getId(), workItemId,
                com.devmind.artifact.model.ArtifactEntity.TYPE_DOC,
                "方案 v" + design.version(), String.valueOf(doc.id()), ArtifactService.PRODUCER_SESSION);
        return design;
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

    // ---------------- CAP-39 手动推送产出为需求文档 ----------------

    /** FR-03 可选文档类型 → 中文名（标题/产物命名共用）。 */
    private static final java.util.Map<String, String> PUBLISH_KIND_LABELS = java.util.Map.of(
            "analysis", "需求分析", "design", "方案设计", "requirement", "需求文档");

    /**
     * FR-03 手动推送会话产出为关联需求文档：create 新建（kind=design 同步落 Design(DRAFT)，
     * 对齐 handleDesignOutput）/ update 目标文档存新版本（校验需求与类型一致，防跨需求改文档）。
     * 两模式均登记产物（analysis→ANALYSIS、其余→DOC，ref=docId）。
     */
    public PublishOutputResult publishOutput(String sessionId, PublishOutputRequest req) {
        SessionEntity session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        if (req == null || req.fileName() == null || req.fileName().isBlank()
                || req.kind() == null || req.requirementId() == null || req.requirementId().isBlank()
                || req.mode() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "fileName/kind/requirementId/mode 必填");
        }
        String kindLabel = PUBLISH_KIND_LABELS.get(req.kind());
        if (kindLabel == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "不支持的文档类型: " + req.kind() + "（仅 analysis/design/requirement）");
        }
        String content = sessionOutputService.findContent(sessionId, req.fileName())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "产出不存在: " + req.fileName() + "（请先在会话工作台「推送产出」中同步）"));
        RequirementEntity requirement = requirementService.requireById(req.requirementId());
        // WI 仅当同属目标需求时透传（DocRequest 有 WI→需求一致性校验，跨需求推送传了会报错）
        String workItemId = session.getWorkItemId() != null && !session.getWorkItemId().isBlank()
                && req.requirementId().equals(session.getRequirementId()) ? session.getWorkItemId() : null;

        if ("update".equals(req.mode())) {
            if (req.docId() == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "更新模式 docId 必填");
            }
            DocDetail target = documentService.get(req.docId(), null);
            if (!req.requirementId().equals(target.requirementId()) || !req.kind().equals(target.kind())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "目标文档与所选需求/文档类型不匹配（文档 #" + req.docId() + " 是 "
                                + target.kind() + "，属需求 " + target.requirementId() + "）");
            }
            String note = req.changeNote() == null || req.changeNote().isBlank()
                    ? "手动推送自会话 " + sessionId : req.changeNote();
            DocDetail doc = documentService.saveVersion(req.docId(), new SaveVersionRequest(content, note));
            registerPublishArtifact(requirement, req.kind(), kindLabel, doc, workItemId);
            return new PublishOutputResult(doc.id(), doc.versionNo(), null);
        }
        if (!"create".equals(req.mode())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "mode 仅支持 create/update");
        }
        String title = req.title() == null || req.title().isBlank()
                ? kindLabel + " - " + requirement.getTitle() : req.title();
        DocDetail doc = documentService.create(new DocRequest(
                req.kind(), requirement.getId(), workItemId, requirement.getProjectId(), title,
                null, null, content));
        String designId = null;
        if ("design".equals(req.kind())) {
            DesignView design = designService.create(requirement.getProjectId(), requirement.getId(),
                    new DesignRequest(doc.id()));
            designId = design.id();
        }
        registerPublishArtifact(requirement, req.kind(), kindLabel, doc, workItemId);
        return new PublishOutputResult(doc.id(), doc.versionNo(), designId);
    }

    /** 推送产物登记：analysis→ANALYSIS、其余→DOC（ref=docId，producer=SESSION，对齐 flow 自动登记形态）。 */
    private void registerPublishArtifact(RequirementEntity req, String kind, String kindLabel,
                                         DocDetail doc, String workItemId) {
        String type = "analysis".equals(kind)
                ? com.devmind.artifact.model.ArtifactEntity.TYPE_ANALYSIS
                : com.devmind.artifact.model.ArtifactEntity.TYPE_DOC;
        artifactService.registerInfo(req.getProjectId(), req.getId(), workItemId, type,
                "REQ-" + req.getSeq() + " " + kindLabel + " v" + doc.versionNo() + "（手动推送）",
                String.valueOf(doc.id()), ArtifactService.PRODUCER_SESSION);
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

    /** 通知正文用的单行摘录（换行压平后截断）。 */
    private static String preview(String s, int max) {
        String one = s.replace('\n', ' ').replace('\r', ' ').strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
