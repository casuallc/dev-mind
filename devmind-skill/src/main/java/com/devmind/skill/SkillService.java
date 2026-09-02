package com.devmind.skill;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.PageView;
import com.devmind.skill.dto.SkillDetailView;
import com.devmind.skill.dto.SkillFileContentView;
import com.devmind.skill.dto.SkillFileRequest;
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
import java.util.Base64;
import java.nio.charset.StandardCharsets;
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

    // ---------------- 附件文件 ----------------

    /** 单文件 512KB / 单 skill 附件合计 2MB / 附件数 50（后续可提为配置项）。 */
    private static final long MAX_FILE_SIZE = 512L * 1024;
    private static final long MAX_TOTAL_SIZE = 2L * 1024 * 1024;
    private static final int MAX_FILES = 50;
    private static final int MAX_PATH_DEPTH = 5;
    private static final String RESERVED_PATH = "skill.md";

    /** contentType 命中则为文本（contentText 有效），其余按二进制（contentBytes 有效） */
    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "application/json", "application/xml", "application/javascript",
            "application/x-sh", "application/yaml", "application/x-yaml", "image/svg+xml");

    public List<SkillFileView> listFiles(String skillId) {
        requireSkill(skillId);
        return fileRepo.findBySkillIdOrderByPathAsc(skillId).stream()
                .map(SkillService::toFileView).toList();
    }

    public SkillFileContentView getFileContent(String skillId, String fileId) {
        requireSkill(skillId);
        SkillFileEntity f = requireFile(skillId, fileId);
        byte[] raw = f.isBinary()
                ? f.getContentBytes()
                : (f.getContentText() == null ? new byte[0] : f.getContentText().getBytes(StandardCharsets.UTF_8));
        return new SkillFileContentView(toFileView(f), Base64.getEncoder().encodeToString(raw));
    }

    /** 新增附件：路径安全校验 + 重名 409 + 大小/数量限制。 */
    public SkillFileView addFile(String skillId, SkillFileRequest req) {
        requireSkill(skillId);
        String path = validateFilePath(req.path());
        if (fileRepo.existsBySkillIdAndPath(skillId, path)) {
            throw new DevMindException(ErrorCode.CONFLICT, "附件已存在: " + path);
        }
        if (fileRepo.countBySkillId(skillId) >= MAX_FILES) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "附件数量超限（≤" + MAX_FILES + "）");
        }
        byte[] raw = decodeBase64(req.contentBase64());
        checkSize(skillId, raw.length, 0);
        SkillFileEntity f = new SkillFileEntity();
        f.setId(UUID.randomUUID().toString().replace("-", ""));
        f.setSkillId(skillId);
        f.setPath(path);
        fillContent(f, raw, req.contentType());
        Instant now = Instant.now();
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        fileRepo.save(f);
        return toFileView(f);
    }

    /** 更新附件：改名（path 可选）/ 改内容（contentBase64 可选），二者至少传一。 */
    public SkillFileView updateFile(String skillId, String fileId, SkillFileRequest req) {
        requireSkill(skillId);
        SkillFileEntity f = requireFile(skillId, fileId);
        boolean changed = false;
        if (req.path() != null && !req.path().isBlank()) {
            String path = validateFilePath(req.path());
            if (!f.getPath().equals(path) && fileRepo.existsBySkillIdAndPath(skillId, path)) {
                throw new DevMindException(ErrorCode.CONFLICT, "附件已存在: " + path);
            }
            f.setPath(path);
            changed = true;
        }
        if (req.contentBase64() != null) {
            byte[] raw = decodeBase64(req.contentBase64());
            checkSize(skillId, raw.length, f.getSize());
            fillContent(f, raw, req.contentType());
            changed = true;
        }
        if (!changed) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "无变更内容（path/contentBase64 至少传一）");
        }
        f.setUpdatedAt(Instant.now());
        fileRepo.save(f);
        return toFileView(f);
    }

    public void deleteFile(String skillId, String fileId) {
        requireSkill(skillId);
        fileRepo.delete(requireFile(skillId, fileId));
    }

    private SkillFileEntity requireFile(String skillId, String fileId) {
        SkillFileEntity f = fileRepo.findById(fileId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "附件不存在: " + fileId));
        if (!f.getSkillId().equals(skillId)) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "附件不属于该 skill: " + fileId);
        }
        return f;
    }

    /**
     * 路径安全校验：拒绝空白/超长/绝对路径/盘符/反斜杠/空段/"."/".."/控制字符/保留名 SKILL.md，
     * 深度 ≤ 5。通过的路径原样返回（各段已校验，无需再归一化）。
     */
    private String validateFilePath(String path) {
        if (path == null || path.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "附件 path 必填");
        }
        String p = path.trim();
        if (p.length() > 255) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "附件 path 过长（≤255）: " + path);
        }
        if (p.startsWith("/") || p.startsWith("\\") || p.matches("^[a-zA-Z]:.*")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "附件 path 须为包内相对路径: " + path);
        }
        if (p.contains("\\")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "附件 path 只用 \"/\" 分隔: " + path);
        }
        String[] segments = p.split("/", -1);
        if (segments.length > MAX_PATH_DEPTH) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "附件 path 层级过深（≤" + MAX_PATH_DEPTH + "）: " + path);
        }
        for (String seg : segments) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "附件 path 含非法路径段: " + path);
            }
            if (seg.chars().anyMatch(Character::isISOControl)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "附件 path 含控制字符: " + path);
            }
        }
        if (RESERVED_PATH.equals(segments[segments.length - 1].toLowerCase())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "SKILL.md 为保留名（本体请编辑 skill 正文），附件不可使用");
        }
        return p;
    }

    private byte[] decodeBase64(String contentBase64) {
        if (contentBase64 == null) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException ex) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "contentBase64 不是合法 Base64");
        }
    }

    /** 大小限制：单文件 ≤512KB；单 skill 附件合计 ≤2MB（更新时扣掉旧文件自身）。 */
    private void checkSize(String skillId, long newSize, long replacedSize) {
        if (newSize > MAX_FILE_SIZE) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "附件大小超限（≤512KB）: " + newSize + " 字节");
        }
        long total = fileRepo.sumSizeBySkillId(skillId) - replacedSize + newSize;
        if (total > MAX_TOTAL_SIZE) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "skill 附件总大小超限（≤2MB），当前将达 " + total + " 字节");
        }
    }

    private void fillContent(SkillFileEntity f, byte[] raw, String contentType) {
        boolean text = contentType != null
                && (contentType.startsWith("text/") || TEXT_MIME_TYPES.contains(contentType));
        f.setBinary(!text);
        if (text) {
            f.setContentText(new String(raw, StandardCharsets.UTF_8));
            f.setContentBytes(null);
        } else {
            f.setContentBytes(raw);
            f.setContentText(null);
        }
        f.setContentType(contentType);
        f.setSize(raw.length);
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
