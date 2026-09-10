package com.devmind.project;

import com.devmind.auth.IdentityService;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitRepoCatalog;
import com.devmind.common.util.GitCli;
import com.devmind.project.config.ProjectProperties;
import com.devmind.project.dto.GitRepoRequest;
import com.devmind.project.dto.GitRepoView;
import com.devmind.project.model.GitRepositoryEntity;
import com.devmind.project.repo.GitRepositoryRepository;
import com.devmind.project.repo.ProjectRepoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * CAP-29 全局代码仓库登记：平台级共享资源，管理写操作仅 ADMIN（SecurityConfig 路由规则）。
 * 同时是 {@link GitRepoCatalog} SPI 实现（worklog 等模块只经 SPI 读）。
 *
 * <p>CLONE 行路径登记前确定性计算（{@code <workspace-root>/_global/<slug>-<sha8>}），
 * 满足 local_path NOT NULL 首插即知；创建后发 {@code gitrepo.clone-requested} 事件，
 * 由 integration 模块监听执行克隆（反向触发防依赖环，同 CAP-23 先例）。</p>
 */
@Service
public class GitRepoService implements GitRepoCatalog {

    private static final Logger log = LoggerFactory.getLogger(GitRepoService.class);

    private final GitRepositoryRepository repoRepo;
    private final ProjectRepoRepository projectRepoRepo;
    private final ProjectProperties props;
    private final IdentityService identity;
    private final DomainEventPublisher eventPublisher;

    public GitRepoService(GitRepositoryRepository repoRepo,
                          ProjectRepoRepository projectRepoRepo,
                          ProjectProperties props,
                          IdentityService identity,
                          DomainEventPublisher eventPublisher) {
        this.repoRepo = repoRepo;
        this.projectRepoRepo = projectRepoRepo;
        this.props = props;
        this.identity = identity;
        this.eventPublisher = eventPublisher;
    }

    // ---------------- GitRepoCatalog SPI ----------------

    @Override
    public List<RepoRef> listAll() {
        return repoRepo.findAll().stream().map(GitRepoService::toRef).toList();
    }

    @Override
    public List<RepoRef> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return repoRepo.findAllById(ids).stream().map(GitRepoService::toRef).toList();
    }

    private static RepoRef toRef(GitRepositoryEntity e) {
        return new RepoRef(e.getId(), e.getName(), e.getLocalPath(), e.getRemoteUrl(),
                e.getDefaultBranch(), e.getStatus(), e.getCloneStatus());
    }

    // ---------------- CRUD（/api/repos，ADMIN） ----------------
    // 注意：会发布 gitrepo.clone-requested 的方法禁 @Transactional（异步监听线程须看到已提交行，
    // 平台红线：异步触发靠 save 自身事务即时提交）。update/delete 无异步触发，保留事务。

    public List<GitRepoView> list() {
        return repoRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(GitRepoView::of).toList();
    }

    public GitRepositoryEntity require(Long id) {
        return repoRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "仓库不存在: " + id));
    }

    public GitRepoView get(Long id) {
        return GitRepoView.of(require(id));
    }

    public GitRepoView create(GitRepoRequest req) {
        GitRepositoryEntity e = upsert(req, identity.currentActor());
        return GitRepoView.of(e);
    }

    @Transactional
    public GitRepoView update(Long id, GitRepoRequest req) {
        GitRepositoryEntity e = require(id);
        e.setName(req.name().strip());
        if (GitRepositoryEntity.SOURCE_LOCAL.equals(e.getSourceType())
                && req.localPath() != null && !req.localPath().isBlank()
                && !req.localPath().strip().equals(e.getLocalPath())) {
            String path = validateGitRepo(req.localPath());
            if (repoRepo.findByLocalPath(path).filter(x -> !x.getId().equals(id)).isPresent()) {
                throw new DevMindException(ErrorCode.CONFLICT, "该路径已登记: " + path);
            }
            e.setLocalPath(path);
        }
        if (req.remoteUrl() != null && !req.remoteUrl().isBlank()
                && !req.remoteUrl().strip().equals(e.getRemoteUrl())) {
            // 改 remoteUrl 不自动重克隆（CAP-23 先例），前端引导显式「重新克隆」
            e.setRemoteUrl(req.remoteUrl().strip());
            e.setRemoteUrlKey(normalizeRemoteUrlKey(req.remoteUrl()));
        }
        if (req.integrationId() != null) {
            e.setIntegrationId(req.integrationId());
        }
        if (req.defaultBranch() != null) {
            e.setDefaultBranch(req.defaultBranch().isBlank() ? null : req.defaultBranch().strip());
        }
        if (req.status() != null) {
            e.setStatus(req.status());
        }
        e.setUpdatedAt(Instant.now());
        return GitRepoView.of(repoRepo.save(e));
    }

    /** 被项目引用时拒绝删除（409）。 */
    @Transactional
    public void delete(Long id) {
        GitRepositoryEntity e = require(id);
        long refs = projectRepoRepo.countByGitRepoId(id);
        if (refs > 0) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "该仓库被 " + refs + " 个项目仓库引用，请先在项目中移除关联");
        }
        repoRepo.delete(e);
        log.info("全局仓库已删除: id={} name={}（磁盘目录 {} 保留，手工清理）", id, e.getName(), e.getLocalPath());
    }

    // ---------------- upsert（项目 addRepo 关联用，CAP-29 FR-02） ----------------

    /**
     * 按规范化 remoteUrl 找/建全局行：存在直接返回；不存在则按 CLONE 新建并触发克隆。
     * 幂等：同 URL 多次添加返回同一行。禁 @Transactional（见上方红线注记）。
     */
    public GitRepositoryEntity upsertCloneByRemoteUrl(String name, String remoteUrl, Long integrationId) {
        validateCloneRemote(remoteUrl, integrationId);
        String key = normalizeRemoteUrlKey(remoteUrl);
        if (key == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "remoteUrl 不合法: " + remoteUrl);
        }
        return repoRepo.findByRemoteUrlKey(key).orElseGet(() -> {
            GitRepositoryEntity e = newRow(name, remoteUrl.strip(), integrationId,
                    GitRepositoryEntity.SOURCE_CLONE, deriveClonePath(remoteUrl).toString());
            e.setCloneStatus(GitRepositoryEntity.CLONE_CLONING);
            GitRepositoryEntity saved = repoRepo.save(e);
            publishCloneRequested(saved);
            return saved;
        });
    }

    /** LOCAL 关联：有 remoteUrl 按 key 找/建（不克隆），否则按 localPath 找/建。 */
    public GitRepositoryEntity upsertLocal(String name, String localPath, String remoteUrl) {
        String key = normalizeRemoteUrlKey(remoteUrl);
        if (key != null) {
            var byKey = repoRepo.findByRemoteUrlKey(key);
            if (byKey.isPresent()) {
                return byKey.get();
            }
        }
        String normalized = Path.of(localPath.strip()).toAbsolutePath().normalize().toString();
        return repoRepo.findByLocalPath(normalized).orElseGet(() -> repoRepo.save(
                newRow(name, remoteUrl == null || remoteUrl.isBlank() ? null : remoteUrl.strip(),
                        null, GitRepositoryEntity.SOURCE_LOCAL, normalized)));
    }

    // ---------------- 内部 ----------------

    private GitRepositoryEntity upsert(GitRepoRequest req, String actor) {
        boolean clone = GitRepositoryEntity.SOURCE_CLONE.equals(req.sourceType());
        if (clone) {
            if (req.remoteUrl() == null || req.remoteUrl().isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "CLONE 模式 remoteUrl 不能为空");
            }
            validateCloneRemote(req.remoteUrl(), req.integrationId());
            String key = normalizeRemoteUrlKey(req.remoteUrl());
            var existing = repoRepo.findByRemoteUrlKey(key);
            if (existing.isPresent()) {
                throw new DevMindException(ErrorCode.CONFLICT, "该远端已登记: " + existing.get().getName());
            }
            GitRepositoryEntity e = newRow(req.name(), req.remoteUrl().strip(), req.integrationId(),
                    GitRepositoryEntity.SOURCE_CLONE, deriveClonePath(req.remoteUrl()).toString());
            e.setCloneStatus(GitRepositoryEntity.CLONE_CLONING);
            e.setCreatedBy(actor);
            GitRepositoryEntity saved = repoRepo.save(e);
            publishCloneRequested(saved);
            return saved;
        }
        String path = validateGitRepo(req.localPath());
        if (repoRepo.findByLocalPath(path).isPresent()) {
            throw new DevMindException(ErrorCode.CONFLICT, "该路径已登记: " + path);
        }
        GitRepositoryEntity e = newRow(req.name(),
                req.remoteUrl() == null || req.remoteUrl().isBlank() ? null : req.remoteUrl().strip(),
                null, GitRepositoryEntity.SOURCE_LOCAL, path);
        e.setCreatedBy(actor);
        return repoRepo.save(e);
    }

    private GitRepositoryEntity newRow(String name, String remoteUrl, Long integrationId,
                                       String sourceType, String localPath) {
        GitRepositoryEntity e = new GitRepositoryEntity();
        e.setName(name.strip());
        e.setLocalPath(localPath);
        e.setRemoteUrl(remoteUrl);
        e.setRemoteUrlKey(normalizeRemoteUrlKey(remoteUrl));
        e.setIntegrationId(integrationId);
        e.setSourceType(sourceType);
        e.setStatus(GitRepositoryEntity.STATUS_ACTIVE);
        e.setCreatedBy(identity.currentActor());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    /** 发布克隆请求事件（integration 监听执行；旁路，失败不影响登记）。 */
    private void publishCloneRequested(GitRepositoryEntity e) {
        eventPublisher.publish(SimpleDomainEvent.of("gitrepo.clone-requested", null, null,
                identity.currentActor(), "全局仓库克隆请求: " + e.getName(),
                "GIT_REPO", String.valueOf(e.getId()), null));
    }

    /** CLONE 目标目录：<workspace-root>/_global/<slug>-<sha8>（同 URL 同路径，确定性）。 */
    public Path deriveClonePath(String remoteUrl) {
        String key = normalizeRemoteUrlKey(remoteUrl);
        String slug = key == null ? "repo" : key.substring(key.lastIndexOf('/') + 1);
        slug = slug.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (slug.isBlank()) {
            slug = "repo";
        }
        return Path.of(props.getWorkspaceRoot(), "_global", slug + "-" + sha8(key == null ? remoteUrl : key));
    }

    /**
     * 规范化 remoteUrl 为 upsert 键：去 userinfo、小写 scheme+host、去默认端口、
     * 去尾斜杠与 .git；git@host:org/repo → host/org/repo；file:// → file/&lt;path&gt;。非法/空返回 null。
     */
    public static String normalizeRemoteUrlKey(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }
        String u = remoteUrl.strip();
        try {
            if (u.contains("://")) {
                var uri = java.net.URI.create(u);
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    // file:// 本地验证通道：无 host，按路径作键
                    String path = uri.getPath() == null ? "" : uri.getPath();
                    return path.isBlank() ? null : stripTail("file" + path);
                }
                String host = uri.getHost() == null ? null : uri.getHost().toLowerCase();
                if (host == null) {
                    return null;
                }
                int port = uri.getPort();
                boolean defaultPort = port == -1 || port == 80 || port == 443;
                String path = uri.getPath() == null ? "" : uri.getPath();
                return stripTail(host + (defaultPort ? "" : ":" + port) + path);
            }
            // git@host:org/repo 形态
            int at = u.indexOf('@');
            int colon = u.indexOf(':', Math.max(at, 0));
            if (at > 0 && colon > at) {
                return stripTail(u.substring(at + 1, colon).toLowerCase() + "/" + u.substring(colon + 1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String stripTail(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.endsWith(".git") ? s.substring(0, s.length() - 4) : s;
    }

    private static String sha8(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** http/https 校验（与 CAP-23 validateCloneRemote 同口径：ssh 拒绝；file:// 仅匿名，供本地验证通道）。 */
    private void validateCloneRemote(String remoteUrl, Long integrationId) {
        String u = remoteUrl == null ? "" : remoteUrl.strip().toLowerCase();
        if (u.startsWith("file://")) {
            if (integrationId != null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "file:// 仅支持匿名克隆（不可选择集成实例）: " + remoteUrl);
            }
            return;
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "仅支持 http/https 远端地址（ssh 形态请先换 https）: " + remoteUrl);
        }
    }

    /** LOCAL：路径存在且 git rev-parse 通过；返回规范化绝对路径。 */
    private String validateGitRepo(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "LOCAL 模式 localPath 不能为空");
        }
        Path p = Path.of(localPath.strip());
        if (!Files.isDirectory(p)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "路径不存在或不是目录: " + p);
        }
        GitCli.Result r = GitCli.run(p, 15, "git", "rev-parse", "--is-inside-work-tree");
        GitCli.requireOk(r, List.of("git", "rev-parse"));
        if (!"true".equals(r.out().strip())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不是 git 仓库: " + p);
        }
        return p.toAbsolutePath().normalize().toString();
    }
}
