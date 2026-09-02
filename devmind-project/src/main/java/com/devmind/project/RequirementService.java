package com.devmind.project;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.JiraManagedFields;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Requirement（CAP-13 研发主线）：业务目标，主线关系的根。只做"身份 + 状态 + 关联"。
 * 状态派生聚合为主（recomputeStatus），仅 ACCEPTANCE→DONE（人工验收）与 CANCELLED 人工翻转，
 * rollup 不覆盖这两个人工终态。
 * 来源（source）：LOCAL 自建 / JIRA 同步。JIRA 来源的托管字段（title/description/type/priority/
 * assignee/reporter/labels/fixVersions/dueDate/externalKey）本地只读——公共 update 静默忽略，
 * 仅同步通道 createFromJira/syncFromJira 可写；本地字段 status/ownerId/docId 同步绝不动。
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

    private static final Set<String> SOURCES = Set.of(
            RequirementEntity.SOURCE_LOCAL,
            RequirementEntity.SOURCE_JIRA);

    private final IdentityService identityService;
    private final ProjectRepository projectRepo;
    private final RequirementRepository requirementRepo;
    private final WorkItemRepository workItemRepo;
    private final DesignRepository designRepo;
    private final RelationRepository relationRepo;
    private final ObjectProvider<RequirementExternalRefLookup> externalRefLookup;

    public RequirementService(ProjectRepository projectRepo,
                              RequirementRepository requirementRepo,
                              WorkItemRepository workItemRepo,
                              DesignRepository designRepo,
                              @Lazy RelationRepository relationRepo,
                              IdentityService identityService,
                              ObjectProvider<RequirementExternalRefLookup> externalRefLookup) {
        this.identityService = identityService;
        this.projectRepo = projectRepo;
        this.requirementRepo = requirementRepo;
        this.workItemRepo = workItemRepo;
        this.designRepo = designRepo;
        this.relationRepo = relationRepo;
        this.externalRefLookup = externalRefLookup;
    }

    /**
     * 需求分页列表：status/type/source 可组合过滤（空=不限），keyword 匹配 title/externalKey，按 seq 倒序。
     * page 从 0 起，size 限制 [1, 200] 防全量拉取打爆内存（Jira 首轮可同步数百上千条 DRAFT）。
     */
    public PageView<RequirementView> list(String projectId, String status, String type, String source,
                                          String keyword, int page, int size) {
        requireProject(projectId);
        String st = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null : normalizeStatus(status);
        String tp = (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type))
                ? null : normalizeType(type);
        String src = (source == null || source.isBlank() || "ALL".equalsIgnoreCase(source))
                ? null : normalizeSource(source);
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);
        PageRequest pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "seq"));
        Page<RequirementEntity> result = requirementRepo.search(projectId, st, tp, src, kw, pageable);
        Map<String, RequirementExternalRefLookup.ExternalRef> refs = refsFor(
                result.getContent().stream().map(RequirementEntity::getId).toList());
        return new PageView<>(result.getContent().stream()
                .map(e -> toView(e, refs.get(e.getId()))).toList(),
                result.getTotalElements(), p, s);
    }

    public RequirementView get(String projectId, String requirementId) {
        RequirementEntity e = requireEntity(projectId, requirementId);
        return toView(e, refsFor(List.of(e.getId())).get(e.getId()));
    }

    /** 创建需求（API 通道，source=LOCAL）：DRAFT 起步，seq 项目内自增（(project_id, seq) 唯一约束兜底）。 */
    public synchronized RequirementView create(String projectId, RequirementRequest req) {
        requireProject(projectId);
        RequirementEntity e = newRequirement(projectId);
        e.setTitle(req.title().trim());
        e.setDescription(MainlineSupport.blankToNull(req.description()));
        e.setType(req.type() == null || req.type().isBlank()
                ? RequirementEntity.TYPE_FEATURE : normalizeType(req.type()));
        e.setOwnerId(MainlineSupport.blankToNull(req.ownerId()));
        e.setDocId(req.docId());
        e.setSource(RequirementEntity.SOURCE_LOCAL);
        applyExtensionFields(e, req.priority(), req.assignee(), req.reporter(),
                req.labels(), req.fixVersions(), parseDueDate(req.dueDate()));
        requirementRepo.save(e);
        log.info("需求已创建: projectId={} code={} title={}", projectId, code(e.getSeq()), e.getTitle());
        return toView(e, null);
    }

    /**
     * 公共更新：JIRA 来源的托管字段本地只读（静默忽略，防老客户端全量 PUT 报错），
     * 仅本地字段 ownerId/docId 对全部来源放行。
     */
    public RequirementView update(String projectId, String requirementId, RequirementRequest req) {
        RequirementEntity e = requireEntity(projectId, requirementId);
        if (RequirementEntity.SOURCE_JIRA.equals(e.getSource())) {
            log.info("Jira 来源需求托管字段本地只读，忽略变更: {} {}", code(e.getSeq()), e.getExternalKey());
        } else {
            if (req.title() != null && !req.title().isBlank()) e.setTitle(req.title().trim());
            if (req.description() != null) e.setDescription(MainlineSupport.blankToNull(req.description()));
            if (req.type() != null && !req.type().isBlank()) e.setType(normalizeType(req.type()));
            if (req.priority() != null) e.setPriority(MainlineSupport.blankToNull(req.priority()));
            if (req.assignee() != null) e.setAssignee(MainlineSupport.blankToNull(req.assignee()));
            if (req.reporter() != null) e.setReporter(MainlineSupport.blankToNull(req.reporter()));
            if (req.labels() != null) e.setLabels(joinCsv(req.labels(), 512));
            if (req.fixVersions() != null) e.setFixVersions(joinCsv(req.fixVersions(), 256));
            if (req.dueDate() != null) {
                e.setDueDate(req.dueDate().isBlank() ? null : parseDueDate(req.dueDate()));
            }
        }
        if (req.ownerId() != null) e.setOwnerId(MainlineSupport.blankToNull(req.ownerId()));
        if (req.docId() != null) e.setDocId(req.docId());
        e.setUpdatedAt(Instant.now());
        return toView(requirementRepo.save(e),
                refsFor(List.of(e.getId())).get(e.getId()));
    }

    /** Jira 同步专用创建：source=JIRA，托管字段全部落列（标题/描述为 Jira 原文，无前缀无尾注）。 */
    public synchronized RequirementView createFromJira(String projectId, JiraManagedFields f) {
        requireProject(projectId);
        RequirementEntity e = newRequirement(projectId);
        e.setSource(RequirementEntity.SOURCE_JIRA);
        applyJiraFields(e, f);
        requirementRepo.save(e);
        log.info("需求已由 Jira 导入: projectId={} code={} key={}", projectId, code(e.getSeq()), f.externalKey());
        return toView(e, null);
    }

    /**
     * Jira 同步专用更新：无条件刷新托管字段（本地只读不会冲突），
     * 本地字段 status/ownerId/docId 绝不动。
     */
    public RequirementView syncFromJira(String projectId, String requirementId, JiraManagedFields f) {
        RequirementEntity e = requireEntity(projectId, requirementId);
        e.setSource(RequirementEntity.SOURCE_JIRA);
        applyJiraFields(e, f);
        e.setUpdatedAt(Instant.now());
        return toView(requirementRepo.save(e), null);
    }

    private RequirementEntity newRequirement(String projectId) {
        RequirementEntity e = new RequirementEntity();
        e.setId(MainlineSupport.shortId());
        e.setProjectId(projectId);
        Long max = requirementRepo.findMaxSeqByProjectId(projectId);
        e.setSeq(max == null ? 1 : max + 1);
        e.setStatus(RequirementEntity.STATUS_DRAFT);
        e.setCreatedBy(identityService.currentActor());
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    /** 托管字段落列（同步通道），title 截 240 防溢出。 */
    private void applyJiraFields(RequirementEntity e, JiraManagedFields f) {
        String title = f.title() == null ? "" : f.title().trim();
        e.setTitle(title.length() > 240 ? title.substring(0, 240) : title);
        e.setDescription(MainlineSupport.blankToNull(f.description()));
        e.setType(f.type() == null || f.type().isBlank()
                ? RequirementEntity.TYPE_FEATURE : normalizeType(f.type()));
        e.setExternalKey(MainlineSupport.blankToNull(f.externalKey()));
        applyExtensionFields(e, f.priority(), f.assignee(), f.reporter(),
                f.labels(), f.fixVersions(), f.dueDate());
    }

    /** 扩展字段落列（Jira 同步与本地自建共用）。 */
    private void applyExtensionFields(RequirementEntity e, String priority, String assignee, String reporter,
                                      List<String> labels, List<String> fixVersions, LocalDate dueDate) {
        e.setPriority(MainlineSupport.blankToNull(priority));
        e.setAssignee(MainlineSupport.blankToNull(assignee));
        e.setReporter(MainlineSupport.blankToNull(reporter));
        e.setLabels(joinCsv(labels, 512));
        e.setFixVersions(joinCsv(fixVersions, 256));
        e.setDueDate(dueDate);
    }

    private static String joinCsv(List<String> items, int maxLen) {
        if (items == null || items.isEmpty()) return null;
        String csv = String.join(",", items.stream()
                .filter(x -> x != null && !x.isBlank())
                .map(String::trim)
                .toList());
        if (csv.isEmpty()) return null;
        return csv.length() > maxLen ? csv.substring(0, maxLen) : csv;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return List.of(csv.split(","));
    }

    private LocalDate parseDueDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception ex) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法截止日期: " + s + "（格式 yyyy-MM-dd）");
        }
    }

    /** 人工状态翻转（验收 DONE / 取消 CANCELLED 等）；只校验状态值合法，不限制转换路径。 */
    public RequirementView updateStatus(String projectId, String requirementId, String status) {
        RequirementEntity e = requireEntity(projectId, requirementId);
        String next = normalizeStatus(status);
        String prev = e.getStatus();
        e.setStatus(next);
        e.setUpdatedAt(Instant.now());
        RequirementView view = toView(requirementRepo.save(e), refsFor(List.of(e.getId())).get(e.getId()));
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

    private String normalizeSource(String source) {
        String s = source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(s)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "非法需求来源: " + source + "（可选 " + String.join("/", SOURCES) + "）");
        }
        return s;
    }

    /** 批量反查外部引用（external_links），端口缺席（project 单独测试）时返回空 map。 */
    private Map<String, RequirementExternalRefLookup.ExternalRef> refsFor(List<String> ids) {
        RequirementExternalRefLookup lookup = externalRefLookup.getIfAvailable();
        if (lookup == null || ids.isEmpty()) {
            return Map.of();
        }
        return lookup.refsFor(ids);
    }

    private String code(Long seq) {
        return "REQ-" + seq;
    }

    private RequirementView toView(RequirementEntity e, RequirementExternalRefLookup.ExternalRef ref) {
        return new RequirementView(e.getId(), e.getProjectId(), e.getSeq(), code(e.getSeq()), e.getTitle(),
                e.getDescription(), e.getStatus(), e.getType(), e.getOwnerId(), e.getDocId(),
                e.getSource(), e.getPriority(), e.getAssignee(), e.getReporter(),
                splitCsv(e.getLabels()), splitCsv(e.getFixVersions()), e.getDueDate(),
                e.getExternalKey(), ref == null ? null : ref.externalUrl(),
                ref == null ? null : ref.remoteStatus(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
