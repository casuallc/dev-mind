package com.devmind.project;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
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
import com.devmind.project.dto.ServerRequest;
import com.devmind.project.dto.ServerView;
import com.devmind.project.dto.WorktreeView;
import com.devmind.project.model.BuildStepEntity;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.Project;
import com.devmind.project.model.ProjectLockEntity;
import com.devmind.project.model.ProjectServerEntity;
import com.devmind.project.model.ReleaseConfigEntity;
import com.devmind.project.repo.BuildStepRepository;
import com.devmind.project.repo.ProjectLockRepository;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.ProjectServerRepository;
import com.devmind.project.repo.ReleaseConfigRepository;
import com.devmind.project.scan.RepoScanner;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final ProjectProperties props;
    private final WorktreeProperties worktreeProps;
    private final ProjectRepository projectRepo;
    private final ProjectServerRepository serverRepo;
    private final BuildStepRepository stepRepo;
    private final ReleaseConfigRepository releaseRepo;
    private final ProjectLockRepository lockRepo;
    private final RepoScanner repoScanner;

    public ProjectService(ProjectProperties props,
                          WorktreeProperties worktreeProps,
                          ProjectRepository projectRepo,
                          ProjectServerRepository serverRepo,
                          BuildStepRepository stepRepo,
                          ReleaseConfigRepository releaseRepo,
                          ProjectLockRepository lockRepo,
                          RepoScanner repoScanner) {
        this.props = props;
        this.worktreeProps = worktreeProps;
        this.projectRepo = projectRepo;
        this.serverRepo = serverRepo;
        this.stepRepo = stepRepo;
        this.releaseRepo = releaseRepo;
        this.lockRepo = lockRepo;
        this.repoScanner = repoScanner;
    }

    /** 启动种子：projects 表为空且配置了 default-path 时，把 yml 预置仓库注册为 id=default 的项目（MVP 平滑迁移）。 */
    @PostConstruct
    public void seedFromConfig() {
        if (projectRepo.count() > 0) {
            return;
        }
        String path = props.getDefaultPath();
        if (path == null || path.isBlank() || !isGitRepo(Path.of(path))) {
            return;
        }
        ProjectEntity e = new ProjectEntity();
        e.setId("default");
        e.setName(props.getDefaultName());
        e.setPath(Path.of(path).toAbsolutePath().normalize().toString());
        e.setDefaultBranch(worktreeProps.getBaseBranch());
        e.setTags(joinTags(props.getDefaultTags()));
        e.setStatus(STATUS_ACTIVE);
        e.setOwnerId("local");
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        projectRepo.save(e);
        log.info("CAP-02 种子项目已注册: id=default name={} path={}", props.getDefaultName(), path);
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
        e.setOwnerId("local");
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        projectRepo.save(e);
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
        }
        if (req.name() != null && !req.name().isBlank()) e.setName(req.name().trim());
        if (req.defaultBranch() != null) e.setDefaultBranch(blankToNull(req.defaultBranch()));
        if (req.tags() != null) e.setTags(joinTags(req.tags()));
        if (req.description() != null) e.setDescription(blankToNull(req.description()));
        if (req.status() != null && !req.status().isBlank()) {
            e.setStatus(req.status().toUpperCase());
        }
        if (req.apiDocSource() != null) e.setApiDocSource(blankToNull(req.apiDocSource()));
        e.setUpdatedAt(Instant.now());
        projectRepo.save(e);
        return toView(e);
    }

    /** 删除项目：级联清理服务器/构建步骤/发版配置/锁。 */
    @Transactional
    public void delete(String id) {
        ProjectEntity e = requireEntity(id);
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
        s.setAccessConfig(blankToNull(req.accessConfig()));
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
                e.getContextSummary(), e.getSummaryGeneratedAt(), e.getOwnerId(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private ServerView toServerView(ProjectServerEntity s) {
        return new ServerView(s.getId(), s.getProjectId(), s.getName(), s.getEnv(), s.getAccessType(),
                s.getAccessConfig(), splitTags(s.getCapabilities()),
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
