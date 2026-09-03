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
import com.devmind.skill.dto.SkillPackageView;
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
import java.util.ArrayList;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    // ---------------- 导出（为注入 agent 工作目录预留） ----------------

    /**
     * 按 ids 导出 skill 包文件树：files[0] 为拼好 frontmatter 的 SKILL.md，其后为附件。
     * 仅导出 ACTIVE（DISABLED 跳过）；id 不存在严格抛 NOT_FOUND。
     */
    public SkillPackageView exportPackages(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "ids 必填");
        }
        List<SkillPackageView.SkillPackageItem> items = ids.stream()
                .map(String::trim).filter(s -> !s.isEmpty()).distinct()
                .map(this::requireSkill)
                .filter(e -> SkillEntity.STATUS_ACTIVE.equals(e.getStatus()))
                .map(this::toPackageItem)
                .toList();
        return new SkillPackageView(items);
    }

    private SkillPackageView.SkillPackageItem toPackageItem(SkillEntity e) {
        List<SkillPackageView.ExportedFile> files = new ArrayList<>();
        files.add(new SkillPackageView.ExportedFile("SKILL.md", false,
                Base64.getEncoder().encodeToString(assembleSkillMd(e).getBytes(StandardCharsets.UTF_8))));
        for (SkillFileEntity f : fileRepo.findBySkillIdOrderByPathAsc(e.getId())) {
            byte[] raw = f.isBinary()
                    ? f.getContentBytes()
                    : (f.getContentText() == null ? new byte[0]
                        : f.getContentText().getBytes(StandardCharsets.UTF_8));
            files.add(new SkillPackageView.ExportedFile(f.getPath(), f.isBinary(),
                    Base64.getEncoder().encodeToString(raw)));
        }
        String projectId = SkillEntity.GLOBAL_PROJECT_ID.equals(e.getProjectId()) ? null : e.getProjectId();
        return new SkillPackageView.SkillPackageItem(e.getId(), e.getName(), e.getScope(), projectId, files);
    }

    /** 拼回完整 SKILL.md：--- frontmatter（name/description + 其余键原样）--- + 正文。 */
    private String assembleSkillMd(SkillEntity e) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("name: ").append(e.getName()).append('\n');
        sb.append("description: ").append(yamlValue(e.getDescription())).append('\n');
        parseExtraFrontmatter(e.getExtraFrontmatter())
                .forEach((k, v) -> sb.append(k).append(": ").append(yamlValue(v)).append('\n'));
        sb.append("---\n");
        if (e.getContentMd() != null && !e.getContentMd().isBlank()) {
            sb.append('\n').append(e.getContentMd());
            if (!e.getContentMd().endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** YAML 单行值：含冒号/# 或首尾空格等不安全字符时加双引号（frontmatter 解析器可直接读）。 */
    private String yamlValue(String v) {
        if (v == null) {
            return "\"\"";
        }
        boolean safe = !v.isEmpty() && v.equals(v.trim())
                && v.chars().noneMatch(c -> c == ':' || c == '#' || c == '\n' || c == '\r' || c == '"')
                && !"-?[]{}&*!|>'%@`".contains(String.valueOf(v.charAt(0)));
        return safe ? v : "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------- 导入（skill 压缩包） ----------------

    /** 导入 zip 防爆：条目 ≤100、解压总量 ≤8MB、单条目 ≤1MB（附件 512KB 由 checkSize 二次把关） */
    private static final int MAX_ZIP_ENTRIES = 100;
    private static final long MAX_ZIP_TOTAL = 8L * 1024 * 1024;
    private static final long MAX_ZIP_ENTRY = 1024 * 1024;

    /** 附件按扩展名推断 contentType（未命中=二进制）；text/* 与 TEXT_MIME_TYPES 决定落 contentText */
    private static final Map<String, String> EXT_MIME = Map.ofEntries(
            Map.entry("md", "text/markdown"), Map.entry("markdown", "text/markdown"),
            Map.entry("txt", "text/plain"), Map.entry("log", "text/plain"),
            Map.entry("json", "application/json"), Map.entry("jsonc", "application/json"),
            Map.entry("yaml", "application/yaml"), Map.entry("yml", "application/yaml"),
            Map.entry("xml", "application/xml"), Map.entry("svg", "image/svg+xml"),
            Map.entry("sh", "application/x-sh"), Map.entry("bash", "application/x-sh"),
            Map.entry("js", "application/javascript"), Map.entry("mjs", "application/javascript"),
            Map.entry("ts", "text/x-typescript"), Map.entry("tsx", "text/x-typescript"),
            Map.entry("jsx", "text/x-jsx"), Map.entry("py", "text/x-python"),
            Map.entry("java", "text/x-java"), Map.entry("go", "text/x-go"),
            Map.entry("rs", "text/x-rust"), Map.entry("sql", "text/x-sql"),
            Map.entry("css", "text/css"), Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"), Map.entry("csv", "text/csv"),
            Map.entry("toml", "text/x-toml"), Map.entry("ini", "text/x-ini"),
            Map.entry("cfg", "text/x-ini"), Map.entry("conf", "text/x-ini"),
            Map.entry("properties", "text/x-properties"), Map.entry("c", "text/x-c"),
            Map.entry("h", "text/x-c"), Map.entry("cpp", "text/x-c++"),
            Map.entry("gitignore", "text/plain"), Map.entry("env", "text/plain"));

    /**
     * 导入 skill zip 压缩包：根目录或单层目录包裹的 SKILL.md 均可（其余文件作附件）。
     * 同名（同 scope+projectId+name）默认 409；overwrite=true 时替换正文/frontmatter/全部附件
     * （tags/status/createdBy 保留原值——zip 不承载这些信息）。synchronized 与 create 同理防并发重名。
     */
    @Transactional
    public synchronized SkillView importPackage(byte[] zipBytes, String scope, String projectId,
                                                boolean overwrite) {
        String sc = requireScope(scope);
        String pid = resolveProjectId(sc, projectId);
        ParsedPackage pkg = parseZip(zipBytes);

        SkillEntity e = skillRepo.findByScopeAndProjectIdAndName(sc, pid, pkg.name()).orElse(null);
        if (e != null && !overwrite) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "同名 skill 已存在: " + sc + "/" + pkg.name() + "（勾选「覆盖已存在同名 skill」可替换）");
        }
        if (e == null) {
            e = new SkillEntity();
            e.setId(UUID.randomUUID().toString().replace("-", ""));
            e.setScope(sc);
            e.setProjectId(pid);
            e.setName(pkg.name());
            e.setStatus(SkillEntity.STATUS_ACTIVE);
            e.setCreatedBy(identityService.currentActor());
            e.setCreatedAt(Instant.now());
        } else {
            fileRepo.deleteBySkillId(e.getId());
        }
        e.setDescription(pkg.description());
        e.setContentMd(pkg.contentMd());
        e.setExtraFrontmatter(writeExtraFrontmatter(pkg.extraFrontmatter()));
        e.setUpdatedAt(Instant.now());
        skillRepo.save(e);

        if (pkg.files().size() > MAX_FILES) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "附件数量超限（≤" + MAX_FILES + "）");
        }
        for (ParsedFile pf : pkg.files()) {
            SkillFileEntity f = new SkillFileEntity();
            f.setId(UUID.randomUUID().toString().replace("-", ""));
            f.setSkillId(e.getId());
            f.setPath(pf.path());
            fillContent(f, pf.content(), pf.contentType());
            Instant now = Instant.now();
            f.setCreatedAt(now);
            f.setUpdatedAt(now);
            fileRepo.save(f);
        }
        log.info("skill 已导入: scope={} projectId={} name={} overwrite={} 附件数={}",
                sc, pid, pkg.name(), overwrite, pkg.files().size());
        return toView(e, pkg.files().size());
    }

    private record ParsedFile(String path, byte[] content, String contentType) {}

    private record ParsedPackage(String name, String description, String contentMd,
                                 Map<String, String> extraFrontmatter, List<ParsedFile> files) {}

    /** 解 zip 到内存（带防爆限制），剥离单层目录前缀后解析 SKILL.md + 附件。 */
    private ParsedPackage parseZip(byte[] zipBytes) {
        Map<String, byte[]> entries = unzip(zipBytes);
        String prefix = detectPrefix(entries);

        byte[] skillMdRaw = null;
        List<ParsedFile> files = new ArrayList<>();
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            String rel = en.getKey().substring(prefix.length());
            if (rel.isEmpty()) {
                continue;
            }
            if ("skill.md".equalsIgnoreCase(rel)) {
                skillMdRaw = en.getValue();
                continue;
            }
            String path = validateFilePath(rel);
            files.add(new ParsedFile(path, en.getValue(), guessContentType(path)));
        }
        if (skillMdRaw == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "压缩包中未找到 SKILL.md（支持根目录或单层目录包裹）");
        }
        if (skillMdRaw.length > MAX_FILE_SIZE) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "SKILL.md 过大（≤512KB）");
        }
        return parseSkillMd(new String(skillMdRaw, StandardCharsets.UTF_8), files);
    }

    /** 解 zip：路径归一化、跳过目录/macOS 垃圾文件，条目/大小超限即拒绝。 */
    private Map<String, byte[]> unzip(byte[] zipBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                if (entries.size() >= MAX_ZIP_ENTRIES) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "压缩包条目过多（≤" + MAX_ZIP_ENTRIES + "）");
                }
                if (ze.isDirectory()) {
                    continue;
                }
                String name = ze.getName().replace('\\', '/');
                while (name.startsWith("./")) {
                    name = name.substring(2);
                }
                // macOS 打包垃圾
                if (name.isEmpty() || name.startsWith("__MACOSX/")
                        || name.endsWith("/.DS_Store") || name.equals(".DS_Store")) {
                    continue;
                }
                byte[] raw = zin.readNBytes((int) (MAX_ZIP_ENTRY + 1));
                if (raw.length > MAX_ZIP_ENTRY) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "压缩包含超大文件（单个 ≤1MB）: " + name);
                }
                total += raw.length;
                if (total > MAX_ZIP_TOTAL) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST, "压缩包解压后总量超限（≤8MB）");
                }
                entries.putIfAbsent(name, raw);
            }
        } catch (java.io.IOException ex) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "无法读取压缩包（须为 zip 格式）: " + ex.getMessage());
        }
        if (entries.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "压缩包为空或不是合法 zip");
        }
        return entries;
    }

    /** 定位 SKILL.md：根目录直接命中则前缀 ""；否则取唯一「<dir>/SKILL.md」的 dir 作前缀。 */
    private String detectPrefix(Map<String, byte[]> entries) {
        for (String name : entries.keySet()) {
            if ("skill.md".equalsIgnoreCase(name)) {
                return "";
            }
        }
        String prefix = null;
        for (String name : entries.keySet()) {
            int slash = name.indexOf('/');
            if (slash > 0 && "skill.md".equalsIgnoreCase(name.substring(slash + 1))) {
                String p = name.substring(0, slash + 1);
                if (prefix != null && !prefix.equals(p)) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "压缩包含多个 skill 目录，无法确定导入目标");
                }
                prefix = p;
            }
        }
        if (prefix == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "压缩包中未找到 SKILL.md（支持根目录或单层目录包裹）");
        }
        // 只保留前缀目录内的条目（忽略目录外的杂项文件）
        String p = prefix;
        entries.keySet().removeIf(k -> !k.startsWith(p));
        return prefix;
    }

    /** 解析 SKILL.md：--- frontmatter（snakeyaml）--- + 正文；name/description 必填并复用结构化校验。 */
    private ParsedPackage parseSkillMd(String text, List<ParsedFile> files) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        if (lines.length < 2 || !lines[0].trim().equals("---")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "SKILL.md 缺少 frontmatter（须以 --- 开头）");
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "SKILL.md frontmatter 未闭合（缺少第二个 ---）");
        }
        Map<String, Object> fm;
        try {
            Object parsed = new org.yaml.snakeyaml.Yaml()
                    .load(String.join("\n", java.util.Arrays.copyOfRange(lines, 1, end)));
            fm = (parsed instanceof Map<?, ?> m) ? toStringKeyMap(m) : Map.of();
        } catch (Exception ex) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "SKILL.md frontmatter YAML 解析失败: " + ex.getMessage());
        }
        String name = validateName(fm.get("name") == null ? null : String.valueOf(fm.get("name")));
        String description = validateDescription(
                fm.get("description") == null ? null : String.valueOf(fm.get("description")));
        Map<String, String> extra = new LinkedHashMap<>();
        fm.forEach((k, v) -> {
            if (!"name".equals(k) && !"description".equals(k)) {
                extra.put(k, frontmatterValue(v));
            }
        });
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, end + 1, lines.length)).strip();
        return new ParsedPackage(name, description, body.isEmpty() ? null : body, extra, files);
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> m) {
        Map<String, Object> r = new LinkedHashMap<>();
        m.forEach((k, v) -> r.put(String.valueOf(k), v));
        return r;
    }

    /** frontmatter 其余键值 → 字符串：标量直转，列表/映射序列化为 JSON 字符串保留。 */
    private String frontmatterValue(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            return objectMapper.writeValueAsString(v);
        }
        return String.valueOf(v);
    }

    private String guessContentType(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return EXT_MIME.get(name.toLowerCase()); // .gitignore/.env 这类全名命中
        }
        return EXT_MIME.get(name.substring(dot + 1).toLowerCase());
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
