package com.devmind.knowledge;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.knowledge.dto.EntryRequest;
import com.devmind.knowledge.dto.EntryView;
import com.devmind.knowledge.dto.EntryViews;
import com.devmind.knowledge.dto.PreviewResult;
import com.devmind.knowledge.dto.ProposalRequest;
import com.devmind.knowledge.dto.ProposalView;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeProposalEntity;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.knowledge.repo.KnowledgeProposalRepository;
import com.devmind.notification.NotificationPublisher;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-04 知识库核心：三层经验（global/project）+ 提案流转（inbox）+ 注入内容预览。
 * 会话层不直接依赖本服务——经 {@link KnowledgeBaseInjector}（KnowledgeInjector SPI）在启动时注入。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeEntryRepository entryRepo;
    private final KnowledgeProposalRepository proposalRepo;
    private final ProjectService projectService;
    private final NotificationPublisher notificationPublisher;

    public KnowledgeBaseService(KnowledgeEntryRepository entryRepo,
                                KnowledgeProposalRepository proposalRepo,
                                ProjectService projectService,
                                NotificationPublisher notificationPublisher) {
        this.entryRepo = entryRepo;
        this.proposalRepo = proposalRepo;
        this.projectService = projectService;
        this.notificationPublisher = notificationPublisher;
    }

    // ---------------- 条目（FR-02/FR-03） ----------------

    public List<EntryView> list(String scope, String projectId, String status) {
        List<KnowledgeEntryEntity> list;
        if (status != null && !status.isBlank()) {
            list = entryRepo.findByStatusOrderByUpdatedAtDesc(status);
        } else if (scope != null && !scope.isBlank()) {
            if ("global".equals(scope)) {
                list = entryRepo.findByScopeOrderByUpdatedAtDesc("global");
            } else if (projectId == null || projectId.isBlank()) {
                // 未指定项目时按 scope 全量（projectId 为 null 的派生查询会因 SQL 空比较匹配不到）
                list = entryRepo.findByScopeOrderByUpdatedAtDesc("project");
            } else {
                list = entryRepo.findByScopeAndProjectIdOrderByUpdatedAtDesc("project", projectId);
            }
        } else {
            list = entryRepo.findByStatusOrderByUpdatedAtDesc("active");
            List<KnowledgeEntryEntity> deprecated = entryRepo.findByStatusOrderByUpdatedAtDesc("deprecated");
            List<KnowledgeEntryEntity> merged = new ArrayList<>(list);
            merged.addAll(deprecated);
            list = merged;
        }
        return list.stream().map(EntryViews::entry).toList();
    }

    public EntryView getEntry(Long id) {
        return EntryViews.entry(requireEntry(id));
    }

    @Transactional
    public EntryView createEntry(EntryRequest req) {
        validateEntry(req);
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        applyEntry(e, req);
        e.setHitCount(0);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(e.getCreatedAt());
        return EntryViews.entry(entryRepo.save(e));
    }

    @Transactional
    public EntryView updateEntry(Long id, EntryRequest req) {
        KnowledgeEntryEntity e = requireEntry(id);
        applyEntry(e, req);
        e.setUpdatedAt(Instant.now());
        return EntryViews.entry(entryRepo.save(e));
    }

    @Transactional
    public void deleteEntry(Long id) {
        entryRepo.delete(requireEntry(id));
    }

    private void applyEntry(KnowledgeEntryEntity e, EntryRequest req) {
        if (req.scope() != null && !req.scope().isBlank()) e.setScope(req.scope());
        if (req.projectId() != null) e.setProjectId(req.projectId().isBlank() ? null : req.projectId());
        if (req.name() != null && !req.name().isBlank()) e.setName(req.name());
        if (req.contentMd() != null) e.setContentMd(req.contentMd());
        if (req.tags() != null) e.setTags(EntryViews.joinTags(req.tags()));
        if (req.sourceProject() != null) e.setSourceProject(req.sourceProject().isBlank() ? null : req.sourceProject());
        if (req.status() != null && !req.status().isBlank()) e.setStatus(req.status());
        if (e.getPath() == null || e.getPath().isBlank()) {
            String scope = e.getScope() == null ? "global" : e.getScope();
            String name = e.getName() == null ? "entry" : e.getName().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
            e.setPath(scope + "/" + name + ".md");
        }
    }

    private void validateEntry(EntryRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "条目名称必填");
        }
        if (req.scope() != null && !"global".equals(req.scope()) && !"project".equals(req.scope())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "scope 必须是 global 或 project");
        }
        if ("project".equals(req.scope()) && (req.projectId() == null || req.projectId().isBlank())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "project 范围必须指定项目");
        }
    }

    /** 全文检索（FR-08）：跨 global + 本项目，含名称/内容/标签。 */
    public List<EntryView> search(String q, String projectId) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return entryRepo.searchActive(q, projectId == null ? "" : projectId)
                .stream().map(EntryViews::entry).toList();
    }

    // ---------------- 注入选择（FR-03 标签过滤） ----------------

    /** 按项目选出将被注入的条目：全局按项目 tags 匹配 + 项目特有。 */
    public List<EntryView> selectEntries(Project project) {
        List<EntryView> used = new ArrayList<>();
        for (KnowledgeEntryEntity e : entryRepo.findByScopeOrderByUpdatedAtDesc("global")) {
            if (!"active".equals(e.getStatus())) {
                continue;
            }
            List<String> tags = EntryViews.splitTags(e.getTags());
            if (!tags.isEmpty()) {
                if (project == null || project.tags() == null || project.tags().isEmpty()
                        || project.tags().stream().noneMatch(tags::contains)) {
                    continue; // 带标签但项目无匹配 → 不注入（防上下文膨胀）
                }
            }
            used.add(EntryViews.entry(e));
        }
        if (project != null) {
            for (KnowledgeEntryEntity e : entryRepo.findByScopeAndProjectIdOrderByUpdatedAtDesc("project", project.id())) {
                if ("active".equals(e.getStatus())) {
                    used.add(EntryViews.entry(e));
                }
            }
        }
        return used;
    }

    /** 注入预览（FR-04）：同真实注入的组装结果，但不写盘、不加 hitCount。 */
    public PreviewResult preview(String projectId, String taskSpec) {
        Project project = projectId == null || projectId.isBlank()
                ? null : projectService.requireProject(projectId);
        List<EntryView> used = selectEntries(project);
        return new PreviewResult(ClaudeMd.assemble(used, taskSpec, null), used);
    }

    /** 对本次注入用到的条目计数（FR-07 清理依据）。 */
    @Transactional
    public void bumpHits(List<EntryView> used) {
        for (EntryView v : used) {
            entryRepo.findById(v.id()).ifPresent(e -> {
                e.setHitCount(e.getHitCount() + 1);
                e.setUpdatedAt(Instant.now());
                entryRepo.save(e);
            });
        }
    }

    // ---------------- 提案（FR-05/FR-06） ----------------

    public List<ProposalView> listProposals(String status) {
        List<KnowledgeProposalEntity> list = (status == null || status.isBlank())
                ? proposalRepo.findByOrderByCreatedAtDesc()
                : proposalRepo.findByStatusOrderByCreatedAtDesc(status);
        return list.stream().map(EntryViews::proposal).toList();
    }

    @Transactional
    public ProposalView createProposal(ProposalRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "提案标题必填");
        }
        if (req.contentMd() == null || req.contentMd().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "提案内容必填");
        }
        KnowledgeProposalEntity p = new KnowledgeProposalEntity();
        p.setTitle(req.title());
        p.setContentMd(req.contentMd());
        p.setTargetScope(req.targetScope() == null || req.targetScope().isBlank() ? "project" : req.targetScope());
        p.setTargetProjectId(req.targetProjectId());
        p.setSourceSessionId(req.sourceSessionId());
        p.setStatus("open");
        p.setCreatedAt(Instant.now());
        p = proposalRepo.save(p);
        // P2 通知：静默进中心（FR-05 不打扰）
        try {
            notificationPublisher.publish(NotificationEvent.of(
                    "KNOWLEDGE_PROPOSAL",
                    req.sourceSessionId() == null ? "-" : req.sourceSessionId(),
                    "有新的经验提案", req.title()));
        } catch (Exception e) {
            log.warn("提案通知发送失败: {}", e.getMessage());
        }
        return EntryViews.proposal(p);
    }

    /** 采纳：target=project → 项目层条目；target=global → 晋升全局（FR-06）。 */
    @Transactional
    public ProposalView adopt(Long id, String target, String projectId) {
        KnowledgeProposalEntity p = requireProposal(id);
        if (!"open".equals(p.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "提案已处理（" + p.getStatus() + "）");
        }
        if (target == null || (!"project".equals(target) && !"global".equals(target))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "target 必须是 project 或 global");
        }
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setName(p.getTitle());
        e.setContentMd(p.getContentMd());
        e.setStatus("active");
        e.setHitCount(0);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        if ("project".equals(target)) {
            String pid = projectId != null && !projectId.isBlank() ? projectId : p.getTargetProjectId();
            if (pid == null || pid.isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "采纳到项目必须指定项目");
            }
            e.setScope("project");
            e.setProjectId(pid);
        } else {
            e.setScope("global");
            e.setSourceProject(p.getTargetProjectId());
        }
        e.setPath(defaultPath(e));
        entryRepo.save(e);

        p.setStatus("adopted");
        p.setAdoptedTo(target);
        p.setAdoptedProjectId("project".equals(target) ? e.getProjectId() : null);
        p.setAdoptedAt(now);
        return EntryViews.proposal(proposalRepo.save(p));
    }

    @Transactional
    public ProposalView reject(Long id) {
        KnowledgeProposalEntity p = requireProposal(id);
        if (!"open".equals(p.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "提案已处理（" + p.getStatus() + "）");
        }
        p.setStatus("rejected");
        p.setAdoptedAt(Instant.now());
        return EntryViews.proposal(proposalRepo.save(p));
    }

    private String defaultPath(KnowledgeEntryEntity e) {
        String name = e.getName() == null ? "entry" : e.getName().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return e.getScope() + "/" + name + ".md";
    }

    private KnowledgeEntryEntity requireEntry(Long id) {
        return entryRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id));
    }

    private KnowledgeProposalEntity requireProposal(Long id) {
        return proposalRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "提案不存在: " + id));
    }
}
