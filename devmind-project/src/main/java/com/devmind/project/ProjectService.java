package com.devmind.project;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.security.ServerCredentialCipher;
import com.devmind.project.config.ProjectProperties;
import com.devmind.project.config.WorktreeProperties;
import com.devmind.project.dto.BuildStepRequest;
import com.devmind.project.dto.BuildStepView;
import com.devmind.project.dto.ContextSummaryView;
import com.devmind.project.dto.LockRequest;
import com.devmind.project.dto.ProjectLockView;
import com.devmind.project.dto.ProjectRequest;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.dto.ReleaseConfigRequest;
import com.devmind.project.dto.ReleaseConfigView;
import com.devmind.project.dto.RepoRequest;
import com.devmind.project.dto.RepoView;
import com.devmind.project.dto.ServerRequest;
import com.devmind.project.dto.ServerView;
import com.devmind.project.dto.WorktreeView;
import com.devmind.project.model.BuildStepEntity;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.Project;
import com.devmind.project.model.ProjectLockEntity;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.model.ProjectServerEntity;
import com.devmind.project.model.ReleaseConfigEntity;
import com.devmind.project.repo.BuildStepRepository;
import com.devmind.project.repo.ProjectLockRepository;
import com.devmind.project.repo.ProjectRepoRepository;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.ProjectServerRepository;
import com.devmind.project.repo.ReleaseConfigRepository;
import com.devmind.project.repo.RequirementRepository;
import com.devmind.project.scan.RepoScanner;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * CAP-02 项目管理：项目 CRUD（FR-01）、标签（FR-02）、服务器（FR-03）、构建配置（FR-04）、
 * 发版配置（FR-05）、API 文档源（FR-06）、上下文摘要（FR-07）、worktree 规范（FR-08）、
 * 项目锁定（FR-09）。
 *
 * <p>同时保留 {@link #requireProject(String)} 供会话能力按 projectId 取项目（向后兼容 MVP）。</p>
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private final IdentityService identityService;
    private final ProjectProperties props;
    private final WorktreeProperties worktreeProps;
    private final ProjectRepository projectRepo;
    private final ProjectRepoRepository repoRepo;
    private final ProjectServerRepository serverRepo;
    private final BuildStepRepository stepRepo;
    private final ReleaseConfigRepository releaseRepo;
    private final ProjectLockRepository lockRepo;
    private final RequirementRepository requirementRepo;
    private final RepoScanner repoScanner;
    /** CAP-07 提供凭证加密实现（可选）；缺省时 accessConfig 明文存储（无 server-adapter 模块时兼容） */
    private final ObjectProvider<ServerCredentialCipher> cipherProvider;

    public ProjectService(IdentityService identityService,
                            ProjectProperties props,
                          WorktreeProperties worktreeProps,
                          ProjectRepository projectRepo,
                          ProjectRepoRepository repoRepo,
                          ProjectServerRepository serverRepo,
                          BuildStepRepository stepRepo,
                          ReleaseConfigRepository releaseRepo,
                          ProjectLockRepository lockRepo,
                          RequirementRepository requirementRepo,
                          RepoScanner repoScanner,
                          ObjectProvider<ServerCredentialCipher> cipherProvider) {
        this.identityService = identityService;
        this.props = props;
        this.worktreeProps = worktreeProps;
        this.projectRepo = projectRepo;
        this.repoRepo = repoRepo;
        this.serverRepo = serverRepo;
        this.stepRepo = stepRepo;
        this.releaseRepo = releaseRepo;
        this.lockRepo = lockRepo;
        this.requirementRepo = requirementRepo;
        this.repoScanner = repoScanner;
        this.cipherProvider = cipherProvider;
    }

    /** 启动种子：projects 表为空且配置了 default-path 时，把 yml 预置仓库注册为 id=default 的项目（MVP 平滑迁移）。 */
    @PostConstruct
    public void seedFromConfig() {
        if (projectRepo.count() == 0) {
            String path = props.getDefaultPath();
            if (path != null && !path.isBlank() && isGitRepo(Path.of(path))) {
                ProjectEntity e = new ProjectEntity();
                e.setId("default");
                e.setName(props.getDefaultName());
                e.setPath(Path.of(path).toAbsolutePath().normalize().toString());
                e.setDefaultBranch(worktreeProps.getBaseBranch());
                e.setTags(joinTags(props.getDefaultTags()));
                e.setStatus(STATUS_ACTIVE);
                e.setOwnerId(identityService.currentActor());
                e.setCreatedBy(identityService.currentActor());
                Instant now = Instant.now();
                e.setCreatedAt(now);
                e.setUpdatedAt(now);
                projectRepo.save(e);
                log.info("CAP-02 种子项目已注册: id=default name={} path={}", props.getDefaultName(), path);
            }
        }
        migrateProjectRepos();
    }

    /**
     * P0-4 多库模型迁移：projects.path → project_repos 主库记录。
     * 对每个尚无仓库记录的项目补一条 is_primary=1 的 CODE 库；projects.path 此后作为主库镜像列维护。
     */
    private void migrateProjectRepos() {
        for (ProjectEntity p : projectRepo.findAll()) {
            if (p.getPath() == null || p.getPath().isBlank() || repoRepo.countByProjectId(p.getId()) > 0) {
                continue;
            }
            ProjectRepoEntity r = new ProjectRepoEntity();
            r.setProjectId(p.getId());
            r.setName(repoDirName(p.getPath()));
            r.setPath(p.getPath());
            r.setDefaultBranch(p.getDefaultBranch());
            r.setRole(ProjectRepoEntity.ROLE_CODE);
            r.setIsPrimary(true);
            r.setSortOrder(0);
            Instant now = Instant.now();
            r.setCreatedAt(now);
            r.setUpdatedAt(now);
            repoRepo.save(r);
            log.info("P0-4 项目仓库已迁移: projectId={} primary={}", p.getId(), p.getPath());
        }
    }

    // ---------------- 项目 CRUD ----------------

    public List<ProjectView> list(String status) {
        List<ProjectEntity> entities = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? projectRepo.findAllByOrderByUpdatedAtDesc()
                : projectRepo.findByStatusOrderByUpdatedAtDesc(status.toUpperCase());
        return entities.stream().map(this::toView).toList();
    }

    public ProjectView get(String id) {
        return toView(requireEntity(id));
    }

    public ProjectView create(ProjectRequest req) {
        Path repoPath = validateRepo(req.path());
        if (projectRepo.countByPath(repoPath.toString()) > 0) {
            throw new DevMindException(ErrorCode.CONFLICT, "该仓库路径已被注册为项目");
        }
        ProjectEntity e = new ProjectEntity();
        e.setId(shortId());
        e.setName(req.name().trim());
        e.setPath(repoPath.toString());
        e.setDefaultBranch(blankToNull(req.defaultBranch()));
        e.setTags(joinTags(req.tags()));
        e.setDescription(blankToNull(req.description()));
        e.setStatus(req.status() == null || req.status().isBlank() ? STATUS_ACTIVE : req.status().toUpperCase());
        e.setApiDocSource(blankToNull(req.apiDocSource()));
        e.setAutoRegressionOnDeploy(req.autoRegressionOnDeploy() != null && req.autoRegressionOnDeploy());
        e.setOwnerId(identityService.currentActor());
                e.setCreatedBy(identityService.currentActor());
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        projectRepo.save(e);
        // P0-4：注册主库记录（projects.path 为主库镜像）
        ProjectRepoEntity primary = newRepoRow(e.getId(), repoDirName(e.getPath()), e.getPath(), null,
                e.getDefaultBranch(), ProjectRepoEntity.ROLE_CODE, true, 0);
        repoRepo.save(primary);
        log.info("项目已创建: id={} name={} path={}", e.getId(), e.getName(), e.getPath());
        return toView(e);
    }

    public ProjectView update(String id, ProjectRequest req) {
        ProjectEntity e = requireEntity(id);
        if (req.path() != null && !req.path().isBlank()) {
            Path repoPath = validateRepo(req.path());
            if (!repoPath.toString().equals(e.getPath()) && projectRepo.countByPath(repoPath.toString()) > 0) {
                throw new DevMindException(ErrorCode.CONFLICT, "该仓库路径已被其他项目注册");
            }
            e.setPath(repoPath.toString());
            // 同步主库记录路径
            repoRepo.findByProjectIdAndIsPrimaryTrue(id).ifPresentOrElse(
                    r -> {
                        r.setPath(e.getPath());
                        r.setUpdatedAt(Instant.now());
                        repoRepo.save(r);
                    },
                    () -> repoRepo.save(newRepoRow(id, repoDirName(e.getPath()), e.getPath(), null,
                            e.getDefaultBranch(), ProjectRepoEntity.ROLE_CODE, true, 0)));
        }
        if (req.name() != null && !req.name().isBlank()) e.setName(req.name().trim());
        if (req.defaultBranch() != null) {
            e.setDefaultBranch(blankToNull(req.defaultBranch()));
            // 默认分支镜像同步到主库记录
            repoRepo.findByProjectIdAndIsPrimaryTrue(id).ifPresent(r -> {
                r.setDefaultBranch(e.getDefaultBranch());
                r.setUpdatedAt(Instant.now());
                repoRepo.save(r);
            });
        }
        if (req.tags() != null) e.setTags(joinTags(req.tags()));
        if (req.description() != null) e.setDescription(blankToNull(req.description()));
        if (req.status() != null && !req.status().isBlank()) {
            e.setStatus(req.status().toUpperCase());
        }
        if (req.apiDocSource() != null) e.setApiDocSource(blankToNull(req.apiDocSource()));
        if (req.autoRegressionOnDeploy() != null) e.setAutoRegressionOnDeploy(req.autoRegressionOnDeploy());
        e.setUpdatedAt(Instant.now());
        projectRepo.save(e);
        return toView(e);
    }

    /** 删除项目：级联清理仓库/需求/服务器/构建步骤/发版配置/锁。 */
    @Transactional
    public void delete(String id) {
        ProjectEntity e = requireEntity(id);
        repoRepo.deleteByProjectId(id);
        requirementRepo.deleteByProjectId(id);
        serverRepo.deleteByProjectId(id);
        stepRepo.deleteByProjectId(id);
        releaseRepo.deleteByProjectId(id);
        lockRepo.deleteById(id);
        projectRepo.delete(e);
        log.info("项目已删除: id={} name={}", id, e.getName());
    }

    // ---------------- 会话兼容 ----------------

    /** 会话按 projectId 取项目；缺失抛 NOT_FOUND。 */
    public Project requireProject(String projectId) {
        return toRecord(requireEntity(projectId));
    }

    /** 缺省项目（种子 default 或第一个 ACTIVE）；无项目时返回 null（fake 模式）。 */
    public Project defaultProject() {
        return projectRepo.findById("default")
                .or(() -> projectRepo.findAllByOrderByUpdatedAtDesc().stream().findFirst())
                .map(this::toRecord).orElse(null);
    }

    // ---------------- 项目仓库（P0-4 多库模型） ----------------

    public List<RepoView> listRepos(String projectId) {
        requireEntity(projectId);
        return repoRepo.findByProjectIdOrderBySortOrderAscIdAsc(projectId).stream().map(this::toRepoView).toList();
    }

    /** 项目主库记录；无仓库记录时抛 NOT_FOUND。 */
    public ProjectRepoEntity primaryRepo(String projectId) {
        return repoRepo.findByProjectIdAndIsPrimaryTrue(projectId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "项目无主库: " + projectId));
    }

    public RepoView addRepo(String projectId, RepoRequest req) {
        requireEntity(projectId);
        Path repoPath = validateRepo(req.path());
        if (repoRepo.countByProjectIdAndPath(projectId, repoPath.toString()) > 0) {
            throw new DevMindException(ErrorCode.CONFLICT, "该仓库已在项目仓库列表中");
        }
        boolean makePrimary = Boolean.TRUE.equals(req.primary()) || repoRepo.countByProjectId(projectId) == 0;
        ProjectRepoEntity r = newRepoRow(projectId, req.name().trim(), repoPath.toString(),
                blankToNull(req.remoteUrl()), blankToNull(req.defaultBranch()),
                normalizeRole(req.role()), makePrimary, req.sortOrder() == null ? 0 : req.sortOrder());
        if (makePrimary) {
            clearPrimary(projectId);
        }
        RepoView view = toRepoView(repoRepo.save(r));
        if (makePrimary) {
            syncPrimaryMirror(projectId);
        }
        log.info("项目仓库已添加: projectId={} name={} path={} primary={}", projectId, r.getName(), r.getPath(), makePrimary);
        return view;
    }

    public RepoView updateRepo(String projectId, Long repoId, RepoRequest req) {
        ProjectRepoEntity r = requireRepo(projectId, repoId);
        if (req.path() != null && !req.path().isBlank()) {
            Path repoPath = validateRepo(req.path());
            if (!repoPath.toString().equals(r.getPath())
                    && repoRepo.countByProjectIdAndPath(projectId, repoPath.toString()) > 0) {
                throw new DevMindException(ErrorCode.CONFLICT, "该仓库已在项目仓库列表中");
            }
            r.setPath(repoPath.toString());
        }
        if (req.name() != null && !req.name().isBlank()) r.setName(req.name().trim());
        if (req.remoteUrl() != null) r.setRemoteUrl(blankToNull(req.remoteUrl()));
        if (req.defaultBranch() != null) r.setDefaultBranch(blankToNull(req.defaultBranch()));
        if (req.role() != null && !req.role().isBlank()) r.setRole(normalizeRole(req.role()));
        if (req.sortOrder() != null) r.setSortOrder(req.sortOrder());
        r.setUpdatedAt(Instant.now());
        if (Boolean.TRUE.equals(req.primary()) && !Boolean.TRUE.equals(r.getIsPrimary())) {
            clearPrimary(projectId);
            r.setIsPrimary(true);
        }
        RepoView view = toRepoView(repoRepo.save(r));
        if (Boolean.TRUE.equals(r.getIsPrimary())) {
            syncPrimaryMirror(projectId);
        }
        return view;
    }

    /** 删除仓库：项目至少保留一个仓库；主库不能删（先把其他仓库设为主库）。 */
    public void deleteRepo(String projectId, Long repoId) {
        ProjectRepoEntity r = requireRepo(projectId, repoId);
        if (repoRepo.countByProjectId(projectId) <= 1) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "项目至少保留一个仓库");
        }
        if (Boolean.TRUE.equals(r.getIsPrimary())) {
            throw new DevMindException(ErrorCode.CONFLICT, "主库不能直接删除，请先将其他仓库设为主库");
        }
        repoRepo.delete(r);
        log.info("项目仓库已删除: projectId={} repoId={} path={}", projectId, repoId, r.getPath());
    }

    /** 指定主库：清除其他主库标记并同步 projects.path/default_branch 镜像。 */
    public RepoView setPrimaryRepo(String projectId, Long repoId) {
        ProjectRepoEntity r = requireRepo(projectId, repoId);
        clearPrimary(projectId);
        r.setIsPrimary(true);
        r.setUpdatedAt(Instant.now());
        RepoView view = toRepoView(repoRepo.save(r));
        syncPrimaryMirror(projectId);
        log.info("项目主库已切换: projectId={} repoId={} path={}", projectId, repoId, r.getPath());
        return view;
    }

    private ProjectRepoEntity requireRepo(String projectId, Long repoId) {
        return repoRepo.findById(repoId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "项目仓库不存在: " + repoId));
    }

    private void clearPrimary(String projectId) {
        for (ProjectRepoEntity x : repoRepo.findByProjectIdOrderBySortOrderAscIdAsc(projectId)) {
            if (Boolean.TRUE.equals(x.getIsPrimary())) {
                x.setIsPrimary(false);
                x.setUpdatedAt(Instant.now());
                repoRepo.save(x);
            }
        }
    }

    /** projects.path / default_branch 作为主库镜像列，供既有消费方（会话/构建/摘要扫描）无感使用。 */
    private void syncPrimaryMirror(String projectId) {
        ProjectEntity p = requireEntity(projectId);
        repoRepo.findByProjectIdAndIsPrimaryTrue(projectId).ifPresent(r -> {
            p.setPath(r.getPath());
            p.setDefaultBranch(r.getDefaultBranch());
            p.setUpdatedAt(Instant.now());
            projectRepo.save(p);
        });
    }

    private ProjectRepoEntity newRepoRow(String projectId, String name, String path, String remoteUrl,
                                         String defaultBranch, String role, boolean primary, int sortOrder) {
        ProjectRepoEntity r = new ProjectRepoEntity();
        r.setProjectId(projectId);
        r.setName(name);
        r.setPath(path);
        r.setRemoteUrl(remoteUrl);
        r.setDefaultBranch(defaultBranch);
        r.setRole(role);
        r.setIsPrimary(primary);
        r.setSortOrder(sortOrder);
        Instant now = Instant.now();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return ProjectRepoEntity.ROLE_CODE;
        }
        String r = role.trim().toUpperCase();
        if (!r.equals(ProjectRepoEntity.ROLE_CODE) && !r.equals(ProjectRepoEntity.ROLE_DOCS)
                && !r.equals(ProjectRepoEntity.ROLE_CONFIG)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "仓库角色仅支持 CODE/DOCS/CONFIG: " + role);
        }
        return r;
    }

    private String repoDirName(String path) {
        Path p = Path.of(path);
        Path fileName = p.getFileName();
        return fileName == null ? path : fileName.toString();
    }

    // ---------------- 上下文摘要（FR-07） ----------------

    public ContextSummaryView getSummary(String id) {
        ProjectEntity e = requireEntity(id);
        return new ContextSummaryView(id, e.getContextSummary() == null ? "" : e.getContextSummary(),
                e.getSummaryGeneratedAt());
    }

    /** 重新扫描仓库生成摘要（自动覆盖，可随后人工修正）。 */
    public ContextSummaryView refreshSummary(String id) {
        ProjectEntity e = requireEntity(id);
        String summary = repoScanner.scan(Path.of(e.getPath()));
        e.setContextSummary(summary);
        e.setSummaryGeneratedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        projectRepo.save(e);
        log.info("项目上下文已重新扫描: id={} len={}", id, summary.length());
        return new ContextSummaryView(id, summary, e.getSummaryGeneratedAt());
    }

    /** 人工修正摘要。 */
    public ContextSummaryView updateSummary(String id, String text) {
        ProjectEntity e = requireEntity(id);
        e.setContextSummary(text == null ? "" : text);
        e.setSummaryGeneratedAt(e.getSummaryGeneratedAt() == null ? Instant.now() : e.getSummaryGeneratedAt());
        e.setUpdatedAt(Instant.now());
        projectRepo.save(e);
        return new ContextSummaryView(id, e.getContextSummary(), e.getSummaryGeneratedAt());
    }

    // ---------------- 服务器（FR-03） ----------------

    public List<ServerView> listServers(String projectId) {
        requireEntity(projectId);
        return serverRepo.findByProjectIdOrderByIdAsc(projectId).stream().map(this::toServerView).toList();
    }

    public ServerView addServer(String projectId, ServerRequest req) {
        requireEntity(projectId);
        ProjectServerEntity s = new ProjectServerEntity();
        applyServer(s, req);
        s.setProjectId(projectId);
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return toServerView(serverRepo.save(s));
    }

    public ServerView updateServer(String projectId, Long serverId, ServerRequest req) {
        ProjectServerEntity s = serverRepo.findById(serverId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "服务器不存在: " + serverId));
        applyServer(s, req);
        s.setUpdatedAt(Instant.now());
        return toServerView(serverRepo.save(s));
    }

    public void deleteServer(String projectId, Long serverId) {
        serverRepo.deleteById(serverId);
    }

    // ---------------- 构建配置（FR-04） ----------------

    public List<BuildStepView> listBuildSteps(String projectId) {
        requireEntity(projectId);
        return stepRepo.findByProjectIdOrderBySortOrderAsc(projectId).stream().map(this::toStepView).toList();
    }

    public BuildStepView addBuildStep(String projectId, BuildStepRequest req) {
        requireEntity(projectId);
        BuildStepEntity s = new BuildStepEntity();
        applyStep(s, req);
        s.setProjectId(projectId);
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return toStepView(stepRepo.save(s));
    }

    public BuildStepView updateBuildStep(String projectId, Long stepId, BuildStepRequest req) {
        BuildStepEntity s = stepRepo.findById(stepId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "构建步骤不存在: " + stepId));
        applyStep(s, req);
        s.setUpdatedAt(Instant.now());
        return toStepView(stepRepo.save(s));
    }

    public void deleteBuildStep(String projectId, Long stepId) {
        stepRepo.deleteById(stepId);
    }

    /** 整表替换：按序更新/新增/删除，用于拖拽排序后一次提交。 */
    @Transactional
    public List<BuildStepView> replaceBuildSteps(String projectId, List<BuildStepRequest> steps) {
        requireEntity(projectId);
        stepRepo.deleteByProjectId(projectId);
        List<BuildStepView> views = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            BuildStepEntity s = new BuildStepEntity();
            applyStep(s, steps.get(i));
            s.setProjectId(projectId);
            s.setSortOrder(i);
            Instant now = Instant.now();
            s.setCreatedAt(now);
            s.setUpdatedAt(now);
            views.add(toStepView(stepRepo.save(s)));
        }
        return views;
    }

    // ---------------- 发版配置（FR-05） ----------------

    public ReleaseConfigView getReleaseConfig(String projectId) {
        requireEntity(projectId);
        return releaseRepo.findByProjectId(projectId).map(this::toReleaseView).orElse(null);
    }

    public ReleaseConfigView saveReleaseConfig(String projectId, ReleaseConfigRequest req) {
        requireEntity(projectId);
        ReleaseConfigEntity e = releaseRepo.findByProjectId(projectId).orElseGet(() -> {
            ReleaseConfigEntity n = new ReleaseConfigEntity();
            n.setProjectId(projectId);
            n.setCreatedAt(Instant.now());
            return n;
        });
        e.setNexusRepo(blankToNull(req.nexusRepo()));
        e.setScriptTemplateRef(blankToNull(req.scriptTemplateRef()));
        e.setVersionRule(blankToNull(req.versionRule()));
        e.setUpdatedAt(Instant.now());
        return toReleaseView(releaseRepo.save(e));
    }

    // ---------------- 项目锁定（FR-09） ----------------

    public ProjectLockView getLock(String projectId) {
        requireEntity(projectId);
        return toLockView(lockOrCreate(projectId));
    }

    public ProjectLockView updateLock(String projectId, LockRequest req) {
        requireEntity(projectId);
        ProjectLockEntity l = lockOrCreate(projectId);
        if (req.maxConcurrent() != null && req.maxConcurrent() > 0) {
            l.setMaxConcurrent(req.maxConcurrent());
            if (l.getActiveWrites() > l.getMaxConcurrent()) {
                l.setActiveWrites(l.getMaxConcurrent());
            }
        }
        l.setUpdatedAt(Instant.now());
        lockRepo.save(l);
        return toLockView(l);
    }

    /** 抢占一个写配额；已达上限抛 CONFLICT（Orchestrator 用）。 */
    public synchronized ProjectLockView claimWrite(String projectId) {
        requireEntity(projectId);
        ProjectLockEntity l = lockOrCreate(projectId);
        if (l.getActiveWrites() >= l.getMaxConcurrent()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "项目 " + projectId + " 并发写已达上限 " + l.getMaxConcurrent());
        }
        l.setActiveWrites(l.getActiveWrites() + 1);
        l.setUpdatedAt(Instant.now());
        return toLockView(lockRepo.save(l));
    }

    /** 释放一个写配额。 */
    public synchronized ProjectLockView releaseWrite(String projectId) {
        requireEntity(projectId);
        ProjectLockEntity l = lockOrCreate(projectId);
        if (l.getActiveWrites() > 0) {
            l.setActiveWrites(l.getActiveWrites() - 1);
        }
        l.setUpdatedAt(Instant.now());
        return toLockView(lockRepo.save(l));
    }

    // ---------------- worktree 列表（FR-08） ----------------

    public List<WorktreeView> listWorktrees(String projectId) {
        ProjectEntity e = requireEntity(projectId);
        Path repo = Path.of(e.getPath());
        Path root = worktreeRoot(repo);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<WorktreeView> views = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                if (!Files.exists(dir.resolve(".git"))) {
                    return; // 不是 worktree（.git 应为文件）
                }
                String branch = gitBranch(dir);
                views.add(new WorktreeView(dir.toString(), branch, dir.getFileName().toString()));
            });
        } catch (IOException ex) {
            log.warn("列出 worktree 失败: {} err={}", root, ex.getMessage());
        }
        views.sort((a, b) -> a.path().compareTo(b.path()));
        return views;
    }

    // ---------------- 内部 ----------------

    private Path worktreeRoot(Path repo) {
        if (worktreeProps.getRoot() != null && !worktreeProps.getRoot().isBlank()) {
            return Path.of(worktreeProps.getRoot()).toAbsolutePath().normalize();
        }
        return repo.resolve(".devmind").resolve("worktrees").toAbsolutePath().normalize();
    }

    private String gitBranch(Path wt) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", wt.toString(), "rev-parse", "--abbrev-ref", "HEAD");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor(20, TimeUnit.SECONDS)) {
                return out.isBlank() ? "?" : out;
            }
            p.destroyForcibly();
            return "?";
        } catch (Exception e) {
            return "?";
        }
    }

    private ProjectLockEntity lockOrCreate(String projectId) {
        return lockRepo.findById(projectId).orElseGet(() -> {
            ProjectLockEntity n = new ProjectLockEntity();
            n.setProjectId(projectId);
            n.setMaxConcurrent(Math.max(1, props.getDefaultMaxConcurrent()));
            n.setActiveWrites(0);
            n.setUpdatedAt(Instant.now());
            return lockRepo.save(n);
        });
    }

    private void applyServer(ProjectServerEntity s, ServerRequest req) {
        s.setName(req.name().trim());
        s.setEnv(blankToNull(req.env()));
        s.setAccessType(req.accessType());
        // CAP-07 FR-07：有凭证加密实现时敏感字段密文落库（幂等：已是密文不再加密）
        ServerCredentialCipher cipher = cipherProvider.getIfAvailable();
        String config = blankToNull(req.accessConfig());
        s.setAccessConfig(cipher != null && config != null ? cipher.encryptConfigJson(config) : config);
        s.setCapabilities(joinTags(req.capabilities()));
        s.setEnabled(req.enabled() == null || req.enabled());
    }

    private void applyStep(BuildStepEntity s, BuildStepRequest req) {
        s.setSortOrder(req.sortOrder());
        s.setName(blankToNull(req.name()));
        s.setCommand(req.command());
        s.setWorkingDir(blankToNull(req.workingDir()));
        s.setLocation(req.location() == null || req.location().isBlank() ? "LOCAL" : req.location().toUpperCase());
    }

    private Path validateRepo(String path) {
        if (path == null || path.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "项目仓库路径不能为空");
        }
        Path repo = Path.of(path).toAbsolutePath().normalize();
        if (!isGitRepo(repo)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不是有效的 git 仓库: " + repo);
        }
        return repo;
    }

    private boolean isGitRepo(Path repo) {
        return Files.isDirectory(repo.resolve(".git")) || Files.isRegularFile(repo.resolve(".git"));
    }

    private ProjectEntity requireEntity(String id) {
        return projectRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "项目不存在: " + id));
    }

    private Project toRecord(ProjectEntity e) {
        return new Project(e.getId(), e.getName(), e.getPath(), e.getDefaultBranch(), splitTags(e.getTags()));
    }

    private ProjectView toView(ProjectEntity e) {
        return new ProjectView(e.getId(), e.getName(), e.getPath(), e.getDefaultBranch(),
                splitTags(e.getTags()), e.getDescription(), e.getStatus(), e.getApiDocSource(),
                e.getAutoRegressionOnDeploy(), e.getContextSummary(), e.getSummaryGeneratedAt(),
                e.getOwnerId(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private RepoView toRepoView(ProjectRepoEntity r) {
        return new RepoView(r.getId(), r.getProjectId(), r.getName(), r.getPath(), r.getRemoteUrl(),
                r.getDefaultBranch(), r.getRole(), Boolean.TRUE.equals(r.getIsPrimary()), r.getSortOrder(),
                r.getCreatedAt(), r.getUpdatedAt());
    }

    private ServerView toServerView(ProjectServerEntity s) {
        // 读取时解密，保证前端编辑回显是明文（重新保存会再加密）
        ServerCredentialCipher cipher = cipherProvider.getIfAvailable();
        String config = s.getAccessConfig();
        return new ServerView(s.getId(), s.getProjectId(), s.getName(), s.getEnv(), s.getAccessType(),
                cipher != null && config != null ? cipher.decryptConfigJson(config) : config,
                splitTags(s.getCapabilities()),
                Boolean.TRUE.equals(s.getEnabled()), s.getCreatedAt(), s.getUpdatedAt());
    }

    private BuildStepView toStepView(BuildStepEntity s) {
        return new BuildStepView(s.getId(), s.getProjectId(), s.getSortOrder(), s.getName(),
                s.getCommand(), s.getWorkingDir(), s.getLocation());
    }

    private ReleaseConfigView toReleaseView(ReleaseConfigEntity e) {
        return new ReleaseConfigView(e.getId(), e.getProjectId(), e.getNexusRepo(),
                e.getScriptTemplateRef(), e.getVersionRule());
    }

    private ProjectLockView toLockView(ProjectLockEntity l) {
        return new ProjectLockView(l.getProjectId(), l.getActiveWrites(), l.getMaxConcurrent());
    }

    private String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return String.join(",", tags.stream().map(String::trim).filter(t -> !t.isBlank()).distinct().toList());
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(t -> !t.isBlank()).toList();
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
