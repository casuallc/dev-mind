package com.devmind.attachment.service;

import com.devmind.attachment.config.AttachmentProperties;
import com.devmind.attachment.dto.AttachmentView;
import com.devmind.attachment.model.AttachmentEntity;
import com.devmind.attachment.repo.AttachmentRepository;
import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CAP-32 附件主服务：上传落盘（tmp + 原子 move，RunnerPackageService 同款模式）+
 * 元数据 + 可见性判定（列表=本人全部+他人 SHARED，ADMIN 全部；raw/删除=owner 或 ADMIN，
 * SHARED 可读）。同步落盘，无异步事务场景。
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    /** contentType → 扩展名兜底映射（原始文件名无扩展名时用） */
    private static final Map<String, String> EXT_BY_MIME = Map.of(
            "image/png", ".png", "image/jpeg", ".jpg", "image/gif", ".gif",
            "image/webp", ".webp", "image/svg+xml", ".svg", "text/plain", ".txt",
            "application/pdf", ".pdf", "application/json", ".json");

    private final AttachmentRepository repo;
    private final AttachmentProperties props;
    private final IdentityService identityService;

    public AttachmentService(AttachmentRepository repo, AttachmentProperties props,
                             IdentityService identityService) {
        this.repo = repo;
        this.props = props;
        this.identityService = identityService;
    }

    // ---------------- 上传 ----------------

    public AttachmentView upload(MultipartFile file, String scope, String description) {
        return upload(file, scope, description, null, null);
    }

    /** CAP-68：tags=逗号分隔自由文本；expireDays=保留天数（null=永久）。 */
    public AttachmentView upload(MultipartFile file, String scope, String description,
                                 String tags, Integer expireDays) {
        if (file == null || file.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "附件内容为空");
        }
        long maxBytes = (long) props.getMaxSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "附件超过大小上限 " + props.getMaxSizeMb() + "MB");
        }
        if (expireDays != null && expireDays <= 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "保留天数必须为正整数");
        }
        String id = newAttachmentId();
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : id;
        String relPath = MONTH_DIR.format(Instant.now().atZone(ZoneId.systemDefault()))
                + "/" + id + extOf(originalName, contentType);

        Path target = rootDir().resolve(relPath).normalize();
        if (!target.startsWith(rootDir())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法存储路径");
        }
        String sha256;
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(id + ".tmp");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = file.getInputStream();
                 var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                }
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            sha256 = HexFormat.of().formatHex(md.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "附件落盘失败: " + e.getMessage(), e);
        }

        AttachmentEntity ent = new AttachmentEntity();
        ent.setId(id);
        ent.setOriginalName(originalName);
        ent.setContentType(contentType);
        ent.setSizeBytes(file.getSize());
        ent.setSha256(sha256);
        ent.setScope(AttachmentEntity.SCOPE_SHARED.equals(scope)
                ? AttachmentEntity.SCOPE_SHARED : AttachmentEntity.SCOPE_PRIVATE);
        ent.setStoragePath(relPath);
        ent.setDescription(normalizeDescription(description));
        ent.setTags(normalizeTags(tags));
        ent.setExpiresAt(expireDays == null ? null
                : Instant.now().plus(expireDays, java.time.temporal.ChronoUnit.DAYS));
        ent.setUploadedBy(identityService.currentActor());
        ent.setCreatedAt(Instant.now());
        repo.save(ent);
        log.info("附件已上传: id={} name={} size={} scope={} by={}",
                id, originalName, file.getSize(), ent.getScope(), ent.getUploadedBy());
        return AttachmentView.of(ent);
    }

    // ---------------- 查询 ----------------

    /** 分页过滤在内存完成（个人平台量级）；type=image|other；tag=单标签精确匹配（CAP-68）。 */
    public List<AttachmentView> list(String scope, String keyword, String type) {
        return list(scope, keyword, type, null);
    }

    public List<AttachmentView> list(String scope, String keyword, String type, String tag) {
        List<AttachmentEntity> visible = isAdmin()
                ? repo.findAll()
                : repo.findByUploadedByOrScopeOrderByCreatedAtDesc(
                        identityService.currentActor(), AttachmentEntity.SCOPE_SHARED);
        return visible.stream()
                .filter(e -> scope == null || scope.isBlank() || scope.equals(e.getScope()))
                .filter(e -> keyword == null || keyword.isBlank() || matchesKeyword(e, keyword))
                .filter(e -> type == null || type.isBlank()
                        || ("image".equals(type) == e.isImage()))
                .filter(e -> tag == null || tag.isBlank() || hasTag(e, tag.trim()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(AttachmentView::of)
                .toList();
    }

    public AttachmentView get(String attachmentId) {
        return AttachmentView.of(requireVisible(attachmentId));
    }

    // ---------------- raw 访问 ----------------

    /** raw 访问：可见性校验后返回实体与盘文件路径；盘文件缺失按 404 处理（容忍元数据与磁盘不一致）。 */
    public RawAttachment raw(String attachmentId) {
        AttachmentEntity ent = requireVisible(attachmentId);
        Path path = rootDir().resolve(ent.getStoragePath()).normalize();
        if (!path.startsWith(rootDir()) || !Files.exists(path)) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "附件文件不存在: " + attachmentId);
        }
        return new RawAttachment(ent, path);
    }

    public record RawAttachment(AttachmentEntity entity, Path path) {}

    // ---------------- scope / 删除 ----------------

    public AttachmentView updateScope(String attachmentId, String scope) {
        AttachmentEntity ent = requireOwned(attachmentId);
        ent.setScope(scope);
        repo.save(ent);
        return AttachmentView.of(ent);
    }

    /** 硬删：删行 + 删盘文件（盘文件缺失仅 warn）。 */
    public void delete(String attachmentId) {
        AttachmentEntity ent = requireOwned(attachmentId);
        deleteEntity(ent);
    }

    /** CAP-68 批量删除：逐项 owner/ADMIN 校验，部分失败不整单回滚，返回逐项结果。 */
    public List<BatchDeleteItemResult> batchDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "ids 不能为空");
        }
        List<BatchDeleteItemResult> results = new java.util.ArrayList<>();
        for (String id : ids) {
            try {
                delete(id);
                results.add(new BatchDeleteItemResult(id, true, null));
            } catch (DevMindException e) {
                results.add(new BatchDeleteItemResult(id, false, e.getMessage()));
            }
        }
        return results;
    }

    public record BatchDeleteItemResult(String attachmentId, boolean ok, String message) {}

    /**
     * CAP-68 元数据更新：description/tags/expiresAt 三字段同语义——null=不变，空白串=清除，
     * 否则覆盖。expiresAt 用全局时间格式「yyyy-MM-dd HH:mm:ss」。
     */
    public AttachmentView updateMeta(String attachmentId, String description, String tags, String expiresAt) {
        AttachmentEntity ent = requireOwned(attachmentId);
        if (description != null) {
            ent.setDescription(normalizeDescription(description));
        }
        if (tags != null) {
            ent.setTags(normalizeTags(tags));
        }
        if (expiresAt != null) {
            ent.setExpiresAt(expiresAt.isBlank() ? null : parseExpireTime(expiresAt));
        }
        repo.save(ent);
        return AttachmentView.of(ent);
    }

    /** CAP-68 定时清理入口（AttachmentCleanupTask 调用）：硬删 expires_at < now，返回删除数。 */
    public int cleanupExpired(Instant now) {
        List<AttachmentEntity> expired = repo.findByExpiresAtBefore(now);
        for (AttachmentEntity ent : expired) {
            deleteEntity(ent);
            log.info("过期附件已清理: id={} name={} expiresAt={}", ent.getId(), ent.getOriginalName(), ent.getExpiresAt());
        }
        return expired.size();
    }

    private void deleteEntity(AttachmentEntity ent) {
        repo.delete(ent);
        Path path = rootDir().resolve(ent.getStoragePath()).normalize();
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("附件盘文件删除失败（元数据已删）: id={} path={} err={}", ent.getId(), path, e.getMessage());
        }
    }

    // ---------------- 内部 ----------------

    /** 读可见性：owner / ADMIN / SHARED 全员；不可见按 NOT_FOUND 隐藏存在性（与 chat 口径一致）。 */
    AttachmentEntity requireVisible(String attachmentId) {
        AttachmentEntity ent = repo.findById(attachmentId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "附件不存在: " + attachmentId));
        String actor = identityService.currentActor();
        boolean readable = AttachmentEntity.SCOPE_SHARED.equals(ent.getScope())
                || actor.equals(ent.getUploadedBy()) || isAdmin();
        if (!readable) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "附件不存在: " + attachmentId);
        }
        return ent;
    }

    /** 写操作（scope/删除）：仅上传者或 ADMIN。 */
    private AttachmentEntity requireOwned(String attachmentId) {
        AttachmentEntity ent = repo.findById(attachmentId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "附件不存在: " + attachmentId));
        String actor = identityService.currentActor();
        if (!actor.equals(ent.getUploadedBy()) && !isAdmin()) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "只有上传者或管理员可以操作该附件");
        }
        return ent;
    }

    private boolean isAdmin() {
        try {
            return identityService.currentUser()
                    .map(u -> UserEntity.ROLE_ADMIN.equals(u.getRole()))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    Path rootDir() {
        String dir = props.getRootDir();
        if (dir == null || dir.isBlank()) {
            dir = Path.of("").toAbsolutePath().resolve("data/attachments").toString();
        }
        return Path.of(dir).toAbsolutePath().normalize();
    }

    /** 关键字匹配：文件名或描述（忽略大小写）。 */
    private static boolean matchesKeyword(AttachmentEntity e, String keyword) {
        String kw = keyword.toLowerCase(Locale.ROOT);
        return (e.getOriginalName() != null && e.getOriginalName().toLowerCase(Locale.ROOT).contains(kw))
                || (e.getDescription() != null && e.getDescription().toLowerCase(Locale.ROOT).contains(kw));
    }

    /** 描述：trim，空白归 null，超长按列长截断。 */
    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.length() > 512 ? trimmed.substring(0, 512) : trimmed;
    }

    /** CAP-68 标签归一：逐 token trim、丢空白、去重保序、逗号重连；结果超列长拒绝。 */
    static String normalizeTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return null;
        }
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (String t : tags.split("[,，]")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        if (tokens.isEmpty()) {
            return null;
        }
        String joined = String.join(",", tokens);
        if (joined.length() > 512) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "标签总长超过 512 字符上限");
        }
        return joined;
    }

    /** 标签精确匹配（单 token；查询侧 trim）。 */
    private static boolean hasTag(AttachmentEntity e, String tag) {
        if (e.getTags() == null) {
            return false;
        }
        for (String t : e.getTags().split(",")) {
            if (t.trim().equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /** CAP-68 过期时间解析：全局时间格式「yyyy-MM-dd HH:mm:ss」（与 JacksonConfig 口径一致）。 */
    private static Instant parseExpireTime(String text) {
        try {
            return java.time.LocalDateTime.parse(text.trim(),
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "过期时间格式应为 yyyy-MM-dd HH:mm:ss: " + text);
        }
    }

    private String newAttachmentId() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private String extOf(String originalName, String contentType) {
        int dot = originalName.lastIndexOf('.');
        if (dot > 0 && dot < originalName.length() - 1 && originalName.length() - dot <= 10) {
            return originalName.substring(dot).toLowerCase(Locale.ROOT);
        }
        return EXT_BY_MIME.getOrDefault(contentType, ".bin");
    }
}
