package com.devmind.project;

import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.WorkItemRequest;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.project.repo.DesignRepository;
import com.devmind.project.repo.RelationRepository;
import com.devmind.project.repo.WorkItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Work Item（CAP-13 研发主线）：工作单元，挂 Requirement 下，可派发给 agent/人执行。
 * 只做"身份 + 状态 + 关联"：状态人工/API 驱动，转换路径不写死（编排属上层 Orchestrator）。
 * 状态变化触发所属 Requirement 的 rollup 重算。
 */
@Service
public class WorkItemService {

    private static final Logger log = LoggerFactory.getLogger(WorkItemService.class);

    private static final Set<String> TYPES = Set.of(
            WorkItemEntity.TYPE_DESIGN,
            WorkItemEntity.TYPE_DEVELOPMENT,
            WorkItemEntity.TYPE_TEST,
            WorkItemEntity.TYPE_DOCUMENT,
            WorkItemEntity.TYPE_REVIEW);

    private static final Set<String> STATUSES = Set.of(
            WorkItemEntity.STATUS_TODO,
            WorkItemEntity.STATUS_IN_PROGRESS,
            WorkItemEntity.STATUS_BLOCKED,
            WorkItemEntity.STATUS_DONE,
            WorkItemEntity.STATUS_CANCELLED);

    private final RequirementService requirementService;
    private final WorkItemRepository workItemRepo;
    private final DesignRepository designRepo;
    private final RelationRepository relationRepo;
    private final DomainEventPublisher eventPublisher;

    public WorkItemService(@Lazy RequirementService requirementService,
                           WorkItemRepository workItemRepo,
                           DesignRepository designRepo,
                           RelationRepository relationRepo,
                           DomainEventPublisher eventPublisher) {
        this.requirementService = requirementService;
        this.workItemRepo = workItemRepo;
        this.designRepo = designRepo;
        this.relationRepo = relationRepo;
        this.eventPublisher = eventPublisher;
    }

    public List<WorkItemView> list(String projectId, String requirementId) {
        requirementService.requireEntity(projectId, requirementId);
        return workItemRepo.findByRequirementIdOrderBySeqAsc(requirementId).stream().map(this::toView).toList();
    }

    public WorkItemView get(String projectId, String requirementId, String workItemId) {
        return toView(requireUnder(projectId, requirementId, workItemId));
    }

    /** 创建工作单元：TODO 起步，seq 项目内自增；创建后触发需求 rollup。 */
    public synchronized WorkItemView create(String projectId, String requirementId, WorkItemRequest req) {
        RequirementEntity requirement = requirementService.requireEntity(projectId, requirementId);
        WorkItemEntity e = new WorkItemEntity();
        e.setId(MainlineSupport.shortId());
        e.setProjectId(projectId);
        e.setRequirementId(requirementId);
        e.setDesignId(requireDesign(projectId, requirementId, req.designId()));
        Long max = workItemRepo.findMaxSeqByProjectId(projectId);
        e.setSeq(max == null ? 1 : max + 1);
        e.setType(normalizeType(req.type()));
        e.setTitle(req.title().trim());
        e.setSpec(MainlineSupport.blankToNull(req.spec()));
        e.setStatus(WorkItemEntity.STATUS_TODO);
        e.setOwnerId(MainlineSupport.blankToNull(req.ownerId()));
        e.setBranchSlug(req.branchSlug() == null || req.branchSlug().isBlank()
                ? MainlineSupport.slugify(req.title()) : MainlineSupport.slugify(req.branchSlug()));
        e.setCreatedBy("local");
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        workItemRepo.save(e);
        log.info("工作单元已创建: projectId={} req={} code={} type={} title={}",
                projectId, requirement.getSeq(), code(e.getSeq()), e.getType(), e.getTitle());
        requirementService.recomputeStatus(requirementId);
        return toView(e);
    }

    public WorkItemView update(String projectId, String requirementId, String workItemId, WorkItemRequest req) {
        WorkItemEntity e = requireUnder(projectId, requirementId, workItemId);
        if (req.type() != null && !req.type().isBlank()) e.setType(normalizeType(req.type()));
        if (req.title() != null && !req.title().isBlank()) e.setTitle(req.title().trim());
        if (req.spec() != null) e.setSpec(MainlineSupport.blankToNull(req.spec()));
        if (req.designId() != null) e.setDesignId(requireDesign(projectId, requirementId, req.designId()));
        if (req.ownerId() != null) e.setOwnerId(MainlineSupport.blankToNull(req.ownerId()));
        if (req.branchSlug() != null) e.setBranchSlug(MainlineSupport.blankToNull(MainlineSupport.slugify(req.branchSlug())));
        e.setUpdatedAt(Instant.now());
        return toView(workItemRepo.save(e));
    }

    /** 状态推进：只校验状态值合法，不限制转换路径；推进后触发需求 rollup，并发布领域事件（CAP-15 编排器订阅）。 */
    public WorkItemView updateStatus(String projectId, String requirementId, String workItemId, String status) {
        WorkItemEntity e = requireUnder(projectId, requirementId, workItemId);
        String next = normalizeStatus(status);
        String prev = e.getStatus();
        e.setStatus(next);
        e.setUpdatedAt(Instant.now());
        WorkItemView view = toView(workItemRepo.save(e));
        log.info("工作单元状态推进: {} {} -> {}", code(e.getSeq()), prev, next);
        requirementService.recomputeStatus(requirementId);
        if (!prev.equals(next)) {
            // success=null：状态翻转是中性事件，通知监听器已将其列入忽略清单，不转通知
            eventPublisher.publish(SimpleDomainEvent.of("workitem.status.changed", projectId, workItemId,
                    e.getCreatedBy(), "工作单元 " + code(e.getSeq()) + " 状态 " + prev + " -> " + next,
                    "WORK_ITEM", workItemId, null));
        }
        return view;
    }

    /** 删除工作单元：清理双向 Relation 边后删除。派生 deleteBy 查询需事务上下文。 */
    @org.springframework.transaction.annotation.Transactional
    public void delete(String projectId, String requirementId, String workItemId) {
        WorkItemEntity e = requireUnder(projectId, requirementId, workItemId);
        relationRepo.deleteByFromTypeAndFromId("work_item", workItemId);
        relationRepo.deleteByToTypeAndToId("work_item", workItemId);
        workItemRepo.delete(e);
        log.info("工作单元已删除: projectId={} code={}", projectId, code(e.getSeq()));
        requirementService.recomputeStatus(requirementId);
    }

    /** 工作分支名（约定）：wi/<seq>-<slug> */
    public String branchName(WorkItemEntity e) {
        return "wi/" + e.getSeq() + (e.getBranchSlug() == null || e.getBranchSlug().isBlank()
                ? "" : "-" + e.getBranchSlug());
    }

    /** 供其他模块按 id 校验工作单元归属（关联字段写入前校验）。 */
    public WorkItemEntity requireEntity(String projectId, String workItemId) {
        return workItemRepo.findById(workItemId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "工作单元不存在: " + workItemId));
    }

    /** 按 id 直查（不要求项目上下文），供会话等用 workItemId 反推 projectId/requirementId。 */
    public WorkItemEntity requireById(String workItemId) {
        return workItemRepo.findById(workItemId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "工作单元不存在: " + workItemId));
    }

    private WorkItemEntity requireUnder(String projectId, String requirementId, String workItemId) {
        WorkItemEntity e = requireEntity(projectId, workItemId);
        if (!e.getRequirementId().equals(requirementId)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "工作单元 " + workItemId + " 不属于需求 " + requirementId);
        }
        return e;
    }

    /** designId 可空（空串视为清除）；非空时校验同需求下存在。 */
    private String requireDesign(String projectId, String requirementId, String designId) {
        if (designId == null || designId.isBlank()) {
            return null;
        }
        designRepo.findById(designId)
                .filter(d -> d.getProjectId().equals(projectId) && d.getRequirementId().equals(requirementId))
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                        "方案 " + designId + " 不属于需求 " + requirementId));
        return designId;
    }

    private String normalizeType(String type) {
        String t = type == null || type.isBlank()
                ? WorkItemEntity.TYPE_DEVELOPMENT : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(t)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "非法工作单元类型: " + type + "（可选 " + String.join("/", TYPES) + "）");
        }
        return t;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "工作单元状态不能为空");
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "非法工作单元状态: " + status + "（可选 " + String.join("/", STATUSES) + "）");
        }
        return s;
    }

    private String code(Long seq) {
        return "WI-" + seq;
    }

    private WorkItemView toView(WorkItemEntity e) {
        return new WorkItemView(e.getId(), e.getProjectId(), e.getRequirementId(), e.getDesignId(),
                e.getSeq(), code(e.getSeq()), e.getType(), e.getTitle(), e.getSpec(), e.getStatus(),
                e.getOwnerId(), e.getBranchSlug(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
