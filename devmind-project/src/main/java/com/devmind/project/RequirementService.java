package com.devmind.project;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.RequirementRequest;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.RequirementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * P0-5 需求实体：项目内主线，每个需求一条独立流程。
 * 只做"身份 + 状态 + 关联"：状态人工/API 驱动，转换规则不写死（流程编排属上层，后续组合叠加）。
 */
@Service
public class RequirementService {

    private static final Logger log = LoggerFactory.getLogger(RequirementService.class);

    private static final Set<String> STATUSES = Set.of(
            RequirementEntity.STATUS_DRAFT,
            RequirementEntity.STATUS_DESIGNING,
            RequirementEntity.STATUS_DEVELOPING,
            RequirementEntity.STATUS_TESTING,
            RequirementEntity.STATUS_ACCEPTANCE,
            RequirementEntity.STATUS_DONE,
            RequirementEntity.STATUS_CANCELLED);

    private final ProjectRepository projectRepo;
    private final RequirementRepository requirementRepo;

    public RequirementService(ProjectRepository projectRepo, RequirementRepository requirementRepo) {
        this.projectRepo = projectRepo;
        this.requirementRepo = requirementRepo;
    }

    public List<RequirementView> list(String projectId, String status) {
        requireProject(projectId);
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return requirementRepo.findByProjectIdOrderBySeqDesc(projectId).stream().map(this::toView).toList();
        }
        return requirementRepo.findByProjectIdAndStatusOrderBySeqDesc(projectId, normalizeStatus(status))
                .stream().map(this::toView).toList();
    }

    public RequirementView get(String projectId, String reqId) {
        return toView(requireEntity(projectId, reqId));
    }

    /** 创建需求：DRAFT 起步，seq 项目内自增（synchronized 防并发重号，(project_id, seq) 唯一约束兜底）。 */
    public synchronized RequirementView create(String projectId, RequirementRequest req) {
        requireProject(projectId);
        RequirementEntity e = new RequirementEntity();
        e.setId(shortId());
        e.setProjectId(projectId);
        Long max = requirementRepo.findMaxSeqByProjectId(projectId);
        e.setSeq(max == null ? 1 : max + 1);
        e.setTitle(req.title().trim());
        e.setDescription(blankToNull(req.description()));
        e.setStatus(RequirementEntity.STATUS_DRAFT);
        e.setOwnerId(blankToNull(req.ownerId()));
        e.setBranchSlug(req.branchSlug() == null || req.branchSlug().isBlank()
                ? slugify(req.title()) : slugify(req.branchSlug()));
        e.setDocId(req.docId());
        e.setCreatedBy("local");
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        requirementRepo.save(e);
        log.info("需求已创建: projectId={} code={} title={}", projectId, code(e.getSeq()), e.getTitle());
        return toView(e);
    }

    public RequirementView update(String projectId, String reqId, RequirementRequest req) {
        RequirementEntity e = requireEntity(projectId, reqId);
        if (req.title() != null && !req.title().isBlank()) e.setTitle(req.title().trim());
        if (req.description() != null) e.setDescription(blankToNull(req.description()));
        if (req.ownerId() != null) e.setOwnerId(blankToNull(req.ownerId()));
        if (req.branchSlug() != null) e.setBranchSlug(blankToNull(slugify(req.branchSlug())));
        if (req.docId() != null) e.setDocId(req.docId());
        e.setUpdatedAt(Instant.now());
        return toView(requirementRepo.save(e));
    }

    /** 状态推进：只校验状态值合法，不限制转换路径（流程规则留给上层组合）。 */
    public RequirementView updateStatus(String projectId, String reqId, String status) {
        RequirementEntity e = requireEntity(projectId, reqId);
        String next = normalizeStatus(status);
        String prev = e.getStatus();
        e.setStatus(next);
        e.setUpdatedAt(Instant.now());
        RequirementView view = toView(requirementRepo.save(e));
        log.info("需求状态推进: {} {} -> {}", code(e.getSeq()), prev, next);
        return view;
    }

    public void delete(String projectId, String reqId) {
        RequirementEntity e = requireEntity(projectId, reqId);
        requirementRepo.delete(e);
        log.info("需求已删除: projectId={} code={}", projectId, code(e.getSeq()));
    }

    /** 需求分支名（P0-6 约定）：req/<seq>-<slug> */
    public String branchName(RequirementEntity e) {
        return "req/" + e.getSeq() + (e.getBranchSlug() == null || e.getBranchSlug().isBlank()
                ? "" : "-" + e.getBranchSlug());
    }

    /** 供其他模块按 id 校验需求归属（关联字段写入前校验）。 */
    public RequirementEntity requireEntity(String projectId, String reqId) {
        return requirementRepo.findById(reqId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + reqId));
    }

    /** 按 id 直查（不要求项目上下文），供会话等用 requirementId 反推 projectId。 */
    public RequirementEntity requireById(String reqId) {
        return requirementRepo.findById(reqId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "需求不存在: " + reqId));
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

    /** 标题/输入转分支 slug：小写字母数字与连字符，最长 48。 */
    private String slugify(String text) {
        if (text == null) {
            return "";
        }
        String slug = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9一-鿿]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    private String code(Long seq) {
        return "REQ-" + seq;
    }

    private RequirementView toView(RequirementEntity e) {
        return new RequirementView(e.getId(), e.getProjectId(), e.getSeq(), code(e.getSeq()), e.getTitle(),
                e.getDescription(), e.getStatus(), e.getOwnerId(), e.getBranchSlug(), e.getDocId(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String shortId() {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(base.charAt(ThreadLocalRandom.current().nextInt(base.length())));
        }
        return sb.toString();
    }
}
