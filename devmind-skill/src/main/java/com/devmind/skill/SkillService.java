package com.devmind.skill;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.PageView;
import com.devmind.skill.dto.SkillDetailView;
import com.devmind.skill.dto.SkillFileView;
import com.devmind.skill.dto.SkillRequest;
import com.devmind.skill.dto.SkillView;
import com.devmind.skill.model.SkillEntity;
import com.devmind.skill.model.SkillFileEntity;
import com.devmind.skill.repo.SkillFileRepository;
import com.devmind.skill.repo.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Skill 管理（基础模块）：Claude Code skill 包本体的 CRUD/启停/分页检索。
 * 作用域 GLOBAL（平台共享，projectId 落库 ""）/ PROJECT（项目私有）。
 * 附件文件与导出见本类后半部分；注入 agent 工作目录为后续迭代（导出端点已为其预留）。
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** Claude Code skill 目录名规则：kebab-case */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final Set<String> SCOPES = Set.of(SkillEntity.SCOPE_GLOBAL, SkillEntity.SCOPE_PROJECT);
    private static final Set<String> STATUSES = Set.of(SkillEntity.STATUS_ACTIVE, SkillEntity.STATUS_DISABLED);

    private final SkillRepository skillRepo;
    private final SkillFileRepository fileRepo;
    private final ProjectService projectService;
    private final IdentityService identityService;
    private final ObjectMapper objectMapper;

    public SkillService(SkillRepository skillRepo,
                        SkillFileRepository fileRepo,
                        ProjectService projectService,
                        IdentityService identityService,
                        ObjectMapper objectMapper) {
        this.skillRepo = skillRepo;
        this.fileRepo = fileRepo;
        this.projectService = projectService;
        this.identityService = identityService;
        this.objectMapper = objectMapper;
    }

    // ---------------- 列表 / 详情 ----------------

    /**
     * 分页列表：scope/projectId/status 可组合过滤（空=不限），keyword 匹配 name/description，
     * 按 updatedAt 倒序。page 从 0 起，size 限制 [1, 200]。
     */
    public PageView<SkillView> list(String scope, String projectId, String status,
                                    String keyword, int page, int size) {
        String sc = normalizeScope(scope);
        String st = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null : normalizeStatus(status);
        String pid = (projectId == null || projectId.isBlank()) ? null : projectId.trim();
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);
        PageRequest pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<SkillEntity> result = skillRepo.search(sc, pid, st, kw, pageable);
        Map<String, Long> counts = fileCounts(
                result.getContent().stream().map(SkillEntity::getId).toList());
        return new PageView<>(result.getContent().stream()
                .map(e -> toView(e, counts.getOrDefault(e.getId(), 0L).intValue())).toList(),
                result.getTotalElements(), p, s);
    }

    public SkillDetailView getDetail(String id) {
        SkillEntity e = requireSkill(id);
        List<SkillFileView> files = fileRepo.findBySkillIdOrderByPathAsc(id).stream()
                .map(SkillService::toFileView).toList();
        return new SkillDetailView(toView(e, files.size()), e.getContentMd(),
                parseExtraFrontmatter(e.getExtraFrontmatter()), files);
    }

    // ---------------- 创建 / 更新 / 启停 / 删除 ----------------

    /** 创建 skill（synchronized 防并发重名，DB 唯一约束兜底）。 */
    public synchronized SkillView create(SkillRequest req) {
        String scope = requireScope(req.scope());
        String projectId = resolveProjectId(scope, req.projectId());
        String name = validateName(req.name());
        String description = validateDescription(req.description());
        if (skillRepo.existsByScopeAndProjectIdAndName(scope, projectId, name)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "同名 skill 已存在: " + scope + "/" + name);
        }
        SkillEntity e = new SkillEntity();
        e.setId(UUID.randomUUID().toString().replace("-", ""));
        e.setScope(scope);
        e.setProjectId(projectId);
        e.setName(name);
        e.setDescription(description);
        e.setContentMd(req.contentMd());
        e.setExtraFrontmatter(writeExtraFrontmatter(req.extraFrontmatter()));
        e.setTags(joinTags(req.tags()));
        e.setStatus(normalizeStatus(req.status()));
        e.setCreatedBy(identityService.currentActor());
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        skillRepo.save(e);
        log.info("skill 已创建: scope={} projectId={} name={}", scope, projectId, name);
        return toView(e, 0);
    }

    /** 更新 skill：scope/projectId 不可变；name 可改（重名校验排除自身）。 */
    public SkillView update(String id, SkillRequest req) {
        SkillEntity e = requireSkill(id);
        String name = validateName(req.name());
        String description = validateDescription(req.description());
        if (!e.getName().equals(name)
                && skillRepo.existsByScopeAndProjectIdAndName(e.getScope(), e.getProjectId(), name)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "同名 skill 已存在: " + e.getScope() + "/" + name);
        }
        e.setName(name);
        e.setDescription(description);
        e.setContentMd(req.contentMd());
        e.setExtraFrontmatter(writeExtraFrontmatter(req.extraFrontmatter()));
        e.setTags(joinTags(req.tags()));
        if (req.status() != null && !req.status().isBlank()) {
            e.setStatus(normalizeStatus(req.status()));
        }
        e.setUpdatedAt(Instant.now());
        skillRepo.save(e);
        log.info("skill 已更新: id={} name={}", id, name);
        return toView(e, (int) fileRepo.countBySkillId(id));
    }

    public SkillView updateStatus(String id, String status) {
        SkillEntity e = requireSkill(id);
        e.setStatus(normalizeStatus(status));
        e.setUpdatedAt(Instant.now());
        skillRepo.save(e);
        return toView(e, (int) fileRepo.countBySkillId(id));
    }

    /** 删除 skill 并级联删除附件。 */
    @Transactional
    public void delete(String id) {
        requireSkill(id);
        fileRepo.deleteBySkillId(id);
        skillRepo.deleteById(id);
        log.info("skill 已删除: id={}", id);
    }

    // ---------------- 校验与装配 ----------------

    public SkillEntity requireSkill(String id) {
        return skillRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "skill 不存在: " + id));
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank() || "ALL".equalsIgnoreCase(scope)) {
            return null;
        }
        String sc = scope.trim().toUpperCase();
        if (!SCOPES.contains(sc)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法 scope: " + scope + "（GLOBAL|PROJECT）");
        }
        return sc;
    }

    private String requireScope(String scope) {
        String sc = normalizeScope(scope);
        if (sc == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "scope 必填（GLOBAL|PROJECT）");
        }
        return sc;
    }

    /** PROJECT 校验项目存在；GLOBAL 统一落 ""（唯一约束要求，见 SkillEntity 类注释）。 */
    private String resolveProjectId(String scope, String projectId) {
        if (SkillEntity.SCOPE_GLOBAL.equals(scope)) {
            return SkillEntity.GLOBAL_PROJECT_ID;
        }
        if (projectId == null || projectId.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "scope=PROJECT 时 projectId 必填");
        }
        projectService.requireProject(projectId.trim());
        return projectId.trim();
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "name 必填");
        }
        String n = name.trim();
        if (n.length() > 64 || !NAME_PATTERN.matcher(n).matches()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "name 须为 kebab-case（小写字母/数字/中划线，1-64 字符）: " + name);
        }
        return n;
    }

    private String validateDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "description 必填（SKILL.md frontmatter 强制要求，是 agent 选择 skill 的依据）");
        }
        String d = description.trim();
        if (d.length() > 500) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "description 过长（≤500）");
        }
        return d;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return SkillEntity.STATUS_ACTIVE;
        }
        String st = status.trim().toUpperCase();
        if (!STATUSES.contains(st)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法 status: " + status + "（ACTIVE|DISABLED）");
        }
        return st;
    }

    /** 其余 frontmatter 键 → JSON；剔除 name/description（由结构化字段承载，防重复）。 */
    private String writeExtraFrontmatter(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        Map<String, String> cleaned = new LinkedHashMap<>(extra);
        cleaned.remove("name");
        cleaned.remove("description");
        if (cleaned.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(cleaned);
    }

    private Map<String, String> parseExtraFrontmatter(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception ex) {
            log.warn("skill extraFrontmatter JSON 解析失败，按空处理: {}", ex.getMessage());
            return Map.of();
        }
    }

    private String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags.stream().map(String::trim).filter(t -> !t.isEmpty()).toList());
    }

    private Map<String, Long> fileCounts(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : skillRepo.countFilesBySkillIds(ids)) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    private SkillView toView(SkillEntity e, int fileCount) {
        String projectId = SkillEntity.GLOBAL_PROJECT_ID.equals(e.getProjectId()) ? null : e.getProjectId();
        List<String> tags = (e.getTags() == null || e.getTags().isBlank())
                ? List.of() : List.of(e.getTags().split(","));
        return new SkillView(e.getId(), e.getScope(), projectId, e.getName(), e.getDescription(),
                tags, e.getStatus(), fileCount, e.getHitCount(), e.getCreatedBy(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    static SkillFileView toFileView(SkillFileEntity f) {
        return new SkillFileView(f.getId(), f.getPath(), f.isBinary(), f.getSize(),
                f.getContentType(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
