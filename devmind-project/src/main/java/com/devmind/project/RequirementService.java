package com.devmind.project;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.PageView;
import com.devmind.project.dto.RequirementRequest;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.project.repo.DesignRepository;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.RelationRepository;
import com.devmind.project.repo.RequirementRepository;
import com.devmind.project.repo.WorkItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Requirement（CAP-13 研发主线）：业务目标，主线关系的根。只做"身份 + 状态 + 关联"。
 * 状态派生聚合为主（recomputeStatus），仅 ACCEPTANCE→DONE（人工验收）与 CANCELLED 人工翻转，
 * rollup 不覆盖这两个人工终态。
 */
@Service
public class RequirementService {

    private static final Logger log = LoggerFactory.getLogger(RequirementService.class);

    private static final Set<String> STATUSES = Set.of(
            RequirementEntity.STATUS_DRAFT,
            RequirementEntity.STATUS_ANALYZING,
            RequirementEntity.STATUS_DESIGNING,
            RequirementEntity.STATUS_IN_PROGRESS,
            RequirementEntity.STATUS_ACCEPTANCE,
            RequirementEntity.STATUS_DONE,
            RequirementEntity.STATUS_CANCELLED);

    private static final Set<String> TYPES = Set.of(
            RequirementEntity.TYPE_FEATURE,
            RequirementEntity.TYPE_BUG,
            RequirementEntity.TYPE_IMPROVEMENT,
            RequirementEntity.TYPE_TASK);

    private final IdentityService identityService;
    private final ProjectRepository projectRepo;
    private final RequirementRepository requirementRepo;
    private final WorkItemRepository workItemRepo;
    private final DesignRepository designRepo;
    private final RelationRepository relationRepo;

    public RequirementService(ProjectRepository projectRepo,
                              RequirementRepository requirementRepo,
                              WorkItemRepository workItemRepo,
                              DesignRepository designRepo,
                              @Lazy RelationRepository relationRepo,
                           IdentityService identityService) {
        this.identityService = identityService;
        this.projectRepo = projectRepo;
        this.requirementRepo = requirementRepo;
        this.workItemRepo = workItemRepo;
        this.designRepo = designRepo;
        this.relationRepo = relationRepo;
    }

    /**
     * 需求分页列表：status/type 可组合过滤（空=不限），按 seq 倒序。
     * page 从 0 起，size 限制 [1, 200] 防全量拉取打爆内存（Jira 首轮可同步数百上千条 DRAFT）。
     */
    public PageView<RequirementView> list(String projectId, String status, String type, int page, int size) {
        requireProject(projectId);
        String st = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null : normalizeStatus(status);
        String tp = (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type))
                ? null : normalizeType(type);
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);
        PageRequest pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "seq"));
        Page<RequirementEntity> result;
        if (st != null && tp != null) {
            result = requirementRepo.findByProjectIdAndStatusAndType(projectId, st, tp, pageable);
        } else if (st != null) {
            result = requirementRepo.findByProjectIdAndStatus(projectId, st, pageable);
        } else if (tp != null) {
            result = requirementRepo.findByProjectIdAndType(projectId, tp, pageable);
        } else {
            result = requirementRepo.findByProjectId(projectId, pageable);
        }
        return new PageView<>(result.getContent().stream().map(this::toView).toList(),
                result.getTotalElements(), p, s);
    }

    public RequirementView get(String projectId, String requirementId) {
        return toView(requireEntity(projectId, requirementId));
    }

    /** 创建需求：DRAFT 起步，seq 项目内自增（synchronized 防并发重号，(project_id, seq) 唯一约束兜底）。 */
    public synchronized RequirementView create(String projectId, RequirementRequest req) {
        requireProject(projectId);
        RequirementEntity e = new RequirementEntity();
        e.setId(MainlineSupport.shortId());
        e.setProjectId(projectId);
        Long max = requirementRepo.findMaxSeqByProjectId(projectId);
        e.setSeq(max == null ? 1 : max + 1);
        e.setTitle(req.title().trim());
        e.setDescription(MainlineSupport.blankToNull(req.description()));
        e.setStatus(RequirementEntity.STATUS_DRAFT);
        e.setType(req.type() == null || req.type().isBlank()
                ? RequirementEntity.TYPE_FEATURE : normalizeType(req.type()));
        e.setOwnerId(MainlineSupport.blankToNull(req.ownerId()));
        e.setDocId(req.docId());
        e.setCreatedBy(identityService.currentActor());
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        requirementRepo.save(e);
        log.info("需求已创建: projectId={} code={} title={}", projectId, code(e.getSeq()), e.getTitle());
        return toView(e);
    }

    public RequirementView update(String projectId, String requirementId, RequirementRequest req) {
        RequirementEntity e = requireEntity(projectId, requirementId);
        if (req.title() != null && !req.title().isBlank()) e.setTitle(req.title().trim());
        if (req.description() != null) e.setDescription(MainlineSupport.blankToNull(req.description()));
        if (req.ownerId() != null) e.setOwnerId(MainlineSupport.blankToNull(req.ownerId()));
        if (req.docId() != null) e.setDocId(req.docId());
        if (req.type() != null && !req.type().isBlank()) e.setType(normalizeType(req.type()));
        e.setUpdatedAt(Instant.now());
        return toView(requirementRepo.save(e));
    }

    /** 人工状态翻转（验收 DONE / 取消 CANCELLED 等）；只校验状态值合法，不限制转换路径。 */
    public RequirementView updateStatus(String projectId, String requirementId, String status) {
        RequirementEntity e = requireEntity(projectId, requirementId);
        String next = normalizeStatus(status);
        String prev = e.getStatus();
        e.setStatus(next);
        e.setUpdatedAt(Instant.now());
        RequirementView view = toView(requirementRepo.save(e));
        log.info("需求状态推进: {} {} -> {}", code(e.getSeq()), prev, next);
        return view;
    }

    /** 删除需求：级联清理其 Work Item / Design / 相关 Relation。派生 deleteBy 查询需事务上下文。 */
    @org.springframework.transaction.annotation.Transactional
    public void delete(String projectId, String requirementId) {
        RequirementEntity e = requireEntity(projectId, requirementId);
        workItemRepo.deleteByRequirementId(requirementId);
        designRepo.deleteByRequirementId(requirementId);
        relationRepo.deleteByFromTypeAndFromId("requirement", requirementId);
        relationRepo.deleteByToTypeAndToId("requirement", requirementId);
        requirementRepo.delete(e);
        log.info("需求已删除: projectId={} code={}", projectId, code(e.getSeq()));
    }

    /**
     * 派生状态重算（rollup）：Work Item 状态变化后调用。
     * 人工终态 DONE / CANCELLED 不覆盖；无 Work Item 时保持现值（DRAFT/ANALYZING 由人工或分析会话推进）。
     */
    public void recomputeStatus(String requirementId) {
        RequirementEntity e = requireById(requirementId);
        if (RequirementEntity.STATUS_DONE.equals(e.getStatus())
                || RequirementEntity.STATUS_CANCELLED.equals(e.getStatus())) {
            return;
        }
        List<WorkItemEntity> items = workItemRepo.findByRequirementIdOrderBySeqAsc(requirementId);
        if (items.isEmpty()) {
            return;
        }
        String next;
        boolean designing = items.stream().anyMatch(w -> WorkItemEntity.TYPE_DESIGN.equals(w.getType())
                && !isTerminal(w.getStatus()));
        boolean active = items.stream().anyMatch(w -> !isTerminal(w.getStatus()));
        if (designing) {
            next = RequirementEntity.STATUS_DESIGNING;
        } else if (active) {
            next = RequirementEntity.STATUS_IN_PROGRESS;
        } else {
            next = RequirementEntity.STATUS_ACCEPTANCE;
        }
        if (!next.equals(e.getStatus())) {
            String prev = e.getStatus();
            e.setStatus(next);
            e.setUpdatedAt(Instant.now());
            requirementRepo.save(e);
            log.info("需求状态 rollup: {} {} -> {}", code(e.getSeq()), prev, next);
        }
    }

    private boolean isTerminal(String status) {
        return WorkItemEntity.STATUS_DONE.equals(status) || WorkItemEntity.STATUS_CANCELLED.equals(status);
    }

    /** 供其他模块按 id 校验需求归属（关联字段写入前校验）。 */
    public RequirementEntity requireEntity(String projectId, String requirementId) {
        return requirementRepo.findById(requirementId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + requirementId));
    }

    /** 按 id 直查（不要求项目上下文），供会话等用 requirementId 反推 projectId。 */
    public RequirementEntity requireById(String requirementId) {
        return requirementRepo.findById(requirementId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + requirementId));
    }

    private void requireProject(String projectId) {
        projectRepo.findById(projectId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "项目不存在: " + projectId));
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "需求状态不能为空");
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "非法需求状态: " + status + "（可选 " + String.join("/", STATUSES) + "）");
        }
        return s;
    }

    private String normalizeType(String type) {
        String t = type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(t)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "非法需求类型: " + type + "（可选 " + String.join("/", TYPES) + "）");
        }
        return t;
    }

    private String code(Long seq) {
        return "REQ-" + seq;
    }

    private RequirementView toView(RequirementEntity e) {
        return new RequirementView(e.getId(), e.getProjectId(), e.getSeq(), code(e.getSeq()), e.getTitle(),
                e.getDescription(), e.getStatus(), e.getType(), e.getOwnerId(), e.getDocId(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
