package com.devmind.worklog.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.integration.service.GitRemoteOps;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.model.WorklogRepoEntity;
import com.devmind.worklog.model.WorklogRepoMemberEntity;
import com.devmind.worklog.model.WorklogUserSettingsEntity;
import com.devmind.worklog.repo.WorklogRepoMemberRepository;
import com.devmind.worklog.repo.WorklogRepoRepository;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CAP-74：工作日志服务端 git 仓的<strong>单写者</strong>收口（FR-03）——平台对仓的全部写入
 * 只经此服务一处：仓级 striped lock 串行化 → 写文件（字节级零规整，迁移幂等的命根子）→
 * git add/commit（GitRemoteOps，message 走 -F 文件防 Windows 中文乱码）。
 *
 * <p>目录契约（FR-02）：{@code <subdir>/daily/yyyy-MM-dd.md}、{@code <subdir>/weekly/yyyy-Www.md}
 * （跨年周用 {@link IsoFields#WEEK_BASED_YEAR}）；commit message = {@code worklog: daily 2026-10-12}
 * 一行摘要 + trailer {@code DevMind-Source}/{@code DevMind-Session}。历史/审计 = git log，
 * 仓内禁止任何派生态索引文件。</p>
 *
 * <p>全程不用 {@code identity.currentActor()}——scheduler/迁移无 SecurityContext，username 显式传参。</p>
 *
 * <p>写后自动推送（2026-10-19 修订）：{@link #writeAndCommit} 产生新 commit 且仓已绑定远端时，
 * 锁外触发 {@link WorklogRemoteBackupService#autoPushAsync} 后台推送（被写入的仓，含团队仓）。</p>
 */
@Service
public class WorklogRepoService {

    private static final Logger log = LoggerFactory.getLogger(WorklogRepoService.class);

    /** 提交来源（commit trailer DevMind-Source 取值） */
    public enum CommitSource {
        AI("ai"), MANUAL("manual"), MIGRATION("migration"), INIT("init"), GIT_IMPORT("git-import");
        private final String trailer;
        CommitSource(String trailer) { this.trailer = trailer; }
        public String trailer() { return trailer; }
    }

    public enum ReportKind { DAILY, WEEKLY }

    /** 写入结果（committed=false = 内容一致零提交，幂等跳过） */
    public record WriteOutcome(String repoKey, String relPath, boolean committed) {}

    /** 报告全文 + git 历史元信息（updatedAt=路径最近提交，createdAt=窗口内最旧提交） */
    public record ReportContent(String repoKey, String relPath, String contentMd,
                                Instant createdAt, Instant updatedAt,
                                String source, String sessionId) {}

    /** 列表行（preview=内容前 N 字；日期从文件名解析，天然有序） */
    public record ReportSummary(LocalDate date, String relPath, String preview,
                                Instant createdAt, Instant updatedAt,
                                String source, String sessionId) {}

    private static final Pattern DAILY_NAME = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})\\.md");
    private static final Pattern WEEKLY_NAME = Pattern.compile("(\\d{4})-W(\\d{2})\\.md");
    private static final Pattern REPO_KEY_SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** commit message 临时文件（固定名，仓级锁保证无并发） */
    private static final String COMMIT_MSG_FILE = ".git/DEVMIND_COMMIT_MSG";
    private static final String LOCAL_BRANCH = "main";

    private final WorklogProperties properties;
    private final WorklogRepoRepository repoRepository;
    private final WorklogRepoMemberRepository memberRepository;
    private final WorklogUserSettingsRepository settingsRepository;
    private final GitRemoteOps git;
    private final ObjectProvider<GitIdentityProvider> identityProvider;
    private final ObjectProvider<RepoGitGateway> gitGatewayProvider;
    /** 写后自动推送（ObjectProvider 探测防循环依赖：RemoteBackupService 反向依赖本服务） */
    private final ObjectProvider<WorklogRemoteBackupService> remoteBackupProvider;

    /** 仓级锁（单实例语义；仓数量有限不清理） */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 读路径 TTL fetch 状态（C6）：仓绑定远端后读操作触发后台 fetch+merge，60s 节流 */
    private static final long FETCH_TTL_MS = 60_000;
    private final ConcurrentHashMap<String, FetchState> fetchStates = new ConcurrentHashMap<>();
    /** 测试钩子：覆盖 TTL（默认 {@link #FETCH_TTL_MS}），否则 60s 无法单测 */
    private long fetchTtlMs = FETCH_TTL_MS;

    void overrideFetchTtlForTest(long ttlMs) {
        this.fetchTtlMs = ttlMs;
    }

    private record FetchState(long at, boolean stale) {}

    public WorklogRepoService(WorklogProperties properties,
                              WorklogRepoRepository repoRepository,
                              WorklogRepoMemberRepository memberRepository,
                              WorklogUserSettingsRepository settingsRepository,
                              GitRemoteOps git,
                              ObjectProvider<GitIdentityProvider> identityProvider,
                              ObjectProvider<RepoGitGateway> gitGatewayProvider,
                              ObjectProvider<WorklogRemoteBackupService> remoteBackupProvider) {
        this.properties = properties;
        this.repoRepository = repoRepository;
        this.memberRepository = memberRepository;
        this.settingsRepository = settingsRepository;
        this.git = git;
        this.identityProvider = identityProvider;
        this.gitGatewayProvider = gitGatewayProvider;
        this.remoteBackupProvider = remoteBackupProvider;
    }

    // ---------------- 仓锁 ----------------

    /** 仓级串行化（30s 等不到 → 409；覆盖建仓/写报告/push/迁移等全部写路径） */
    public <T> T withLock(String repoKey, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(repoKey, k -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DevMindException(ErrorCode.CONFLICT, "仓锁等待被中断");
        }
        if (!acquired) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "工作日志仓 " + repoKey + " 正忙（另一写入进行中），请稍后重试");
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    // ---------------- 仓建模 ----------------

    /** 个人仓 repo_key：personal-<sanitized-username>（[^A-Za-z0-9._-] → '-'，总长截 64） */
    public static String personalRepoKey(String username) {
        String sanitized = username == null ? "" : username.replaceAll("[^A-Za-z0-9._-]", "-");
        if (sanitized.isBlank()) {
            sanitized = "user";
        }
        String key = "personal-" + sanitized;
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

    /**
     * 懒建个人仓（幂等）：锁内 double-check → git init + 骨架 commit 成功再插 DB
     * （撞 UK = 并发被另一线程建成，重读返回）。骨架 = README（目录契约）+
     * {@code <username>/daily/.gitkeep} + {@code <username>/weekly/.gitkeep}。
     */
    public WorklogRepoEntity ensurePersonalRepo(String username) {
        String key = personalRepoKey(username);
        return withLock(key, () -> {
            Optional<WorklogRepoEntity> existing = repoRepository.findByRepoKey(key);
            if (existing.isPresent()) {
                return existing.get();
            }
            Path dir = repoDir(key);
            GitRemoteOps.GitResult init = git.initRepo(dir.toString(), LOCAL_BRANCH);
            if (!init.ok()) {
                throw new DevMindException(ErrorCode.INTERNAL, "工作日志仓初始化失败: " + init.output());
            }
            writeSkeleton(dir, username);
            WorklogRepoEntity entity = new WorklogRepoEntity();
            entity.setRepoKey(key);
            entity.setRemoteBranch(LOCAL_BRANCH);
            entity.setCreatedBy(username);
            entity.setCreatedAt(Instant.now());
            WorklogRepoEntity saved;
            try {
                saved = repoRepository.save(entity);
                WorklogRepoMemberEntity member = new WorklogRepoMemberEntity();
                member.setRepoId(saved.getId());
                member.setUserId(username);
                member.setSubdir(username);
                member.setCreatedAt(Instant.now());
                memberRepository.save(member);
            } catch (DataIntegrityViolationException race) {
                // 并发撞 UK：另一线程已建成，重读
                return repoRepository.findByRepoKey(key).orElseThrow(() -> race);
            }
            log.info("工作日志个人仓已建: user={} key={} dir={}", username, key, dir);
            return saved;
        });
    }

    /** 活动仓（写入路由 FR-01）：settings.active_repo_key 非空且仓存在用之，否则个人仓（懒建） */
    public WorklogRepoEntity activeRepoFor(String username) {
        String activeKey = settingsRepository.findByUserId(username)
                .map(WorklogUserSettingsEntity::getActiveRepoKey)
                .filter(k -> !k.isBlank()).orElse(null);
        if (activeKey != null) {
            Optional<WorklogRepoEntity> active = repoRepository.findByRepoKey(activeKey.trim());
            if (active.isPresent()) {
                return active.get();
            }
            log.warn("active_repo_key={} 对应仓不存在，回退个人仓: user={}", activeKey, username);
        }
        return ensurePersonalRepo(username);
    }

    /** 读路径用：活动仓查找（不建仓——个人仓未建 = 尚无报告）；绑定远端的仓顺带触发 TTL 后台 fetch */
    public Optional<WorklogRepoEntity> findActiveRepo(String username) {
        String activeKey = settingsRepository.findByUserId(username)
                .map(WorklogUserSettingsEntity::getActiveRepoKey)
                .filter(k -> !k.isBlank()).orElse(null);
        if (activeKey != null) {
            Optional<WorklogRepoEntity> active = repoRepository.findByRepoKey(activeKey.trim());
            if (active.isPresent()) {
                maybeFetchAsync(active.get());
                return active;
            }
        }
        Optional<WorklogRepoEntity> personal = repoRepository.findByRepoKey(personalRepoKey(username));
        personal.ifPresent(this::maybeFetchAsync);
        return personal;
    }

    /** 按键查仓（不建仓；设置校验等读路径用） */
    public Optional<WorklogRepoEntity> findRepo(String repoKey) {
        return repoRepository.findByRepoKey(repoKey);
    }

    /** 个人仓查找（不建仓；设置页读远端绑定用） */
    public Optional<WorklogRepoEntity> findPersonalRepo(String username) {
        return repoRepository.findByRepoKey(personalRepoKey(username));
    }

    /** 按键取仓（不存在 404；resolve/成员管理端点用） */
    public WorklogRepoEntity requireRepo(String repoKey) {
        return repoRepository.findByRepoKey(repoKey)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "工作日志仓不存在: " + repoKey));
    }

    /** 成员判定（resolve/成员管理端点鉴权用） */
    public boolean isMember(WorklogRepoEntity repo, String username) {
        return memberRepository.findByRepoIdAndUserId(repo.getId(), username).isPresent();
    }

    /** 仓行更新（远端绑定等字段变更；写路径之外的 DB 单行保存不加锁） */
    public WorklogRepoEntity saveRepo(WorklogRepoEntity repo) {
        return repoRepository.save(repo);
    }

    /** HEAD 最近提交时间（空间视图 lastCommitAt；空仓/异常 → null） */
    public Instant lastCommitAt(WorklogRepoEntity repo) {
        GitRemoteOps.GitResult r = git.headCommitAt(repoDir(repo.getRepoKey()).toString());
        if (!r.ok() || r.output() == null || r.output().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(r.output().trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 团队仓（CAP-74 M2） ----------------

    /** 仓列表行（设置页「活动仓」切换用） */
    public record RepoSummary(String repoKey, String createdBy, boolean personal,
                              String remoteUrl, String remoteBranch, Instant lastCommitAt) {}

    /** 成员行（成员管理展示用） */
    public record RepoMemberView(String username, boolean owner) {}

    /** 我所属的仓列表（成员关系驱动，含个人仓与团队仓） */
    public List<RepoSummary> listMyRepos(String username) {
        List<RepoSummary> out = new ArrayList<>();
        for (WorklogRepoMemberEntity m : memberRepository.findByUserId(username)) {
            Optional<WorklogRepoEntity> repo = repoRepository.findById(m.getRepoId());
            if (repo.isEmpty()) {
                continue;
            }
            WorklogRepoEntity r = repo.get();
            out.add(new RepoSummary(r.getRepoKey(), r.getCreatedBy(),
                    r.getRepoKey().equals(personalRepoKey(username)),
                    r.getRemoteUrl(), r.getRemoteBranch(), lastCommitAt(r)));
        }
        out.sort(Comparator.comparing(RepoSummary::repoKey));
        return out;
    }

    /** 仓成员列表（仓成员可看） */
    public List<RepoMemberView> listMembers(String repoKey, String actor) {
        WorklogRepoEntity repo = requireRepo(repoKey);
        if (!isMember(repo, actor)) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "你不是仓 " + repoKey + " 的成员");
        }
        return memberRepository.findByRepoId(repo.getId()).stream()
                .map(m -> new RepoMemberView(m.getUserId(), m.getUserId().equals(repo.getCreatedBy())))
                .sorted(Comparator.comparing(RepoMemberView::username))
                .toList();
    }

    /**
     * 仓主加成员（按 username）：锁内先写该成员骨架子目录（daily/weekly .gitkeep）并 commit，
     * 再落成员行——目录先存在于仓，读路径即时可见。
     */
    public void addMember(String repoKey, String actor, String username) {
        WorklogRepoEntity repo = requireRepo(repoKey);
        requireOwner(repo, actor);
        if (username == null || username.isBlank()
                || username.contains("..") || username.contains("/") || username.contains("\\")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法成员用户名: " + username);
        }
        withLock(repoKey, () -> {
            if (memberRepository.findByRepoIdAndUserId(repo.getId(), username).isPresent()) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "用户 " + username + " 已是仓 " + repoKey + " 的成员");
            }
            commitMemberSkeleton(repoDir(repoKey), username);
            WorklogRepoMemberEntity m = new WorklogRepoMemberEntity();
            m.setRepoId(repo.getId());
            m.setUserId(username);
            m.setSubdir(username);
            m.setCreatedAt(Instant.now());
            memberRepository.save(m);
            log.info("工作日志仓加成员: repo={} member={} by={}", repoKey, username, actor);
            return null;
        });
    }

    /** 仓主移除成员（仅删成员行，仓内历史文件保留——git log 审计不受影响） */
    public void removeMember(String repoKey, String actor, String username) {
        WorklogRepoEntity repo = requireRepo(repoKey);
        requireOwner(repo, actor);
        if (repo.getCreatedBy().equals(username)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不能移除仓主本人");
        }
        WorklogRepoMemberEntity m = memberRepository.findByRepoIdAndUserId(repo.getId(), username)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "用户 " + username + " 不是仓 " + repoKey + " 的成员"));
        memberRepository.delete(m);
        log.info("工作日志仓移除成员: repo={} member={} by={}", repoKey, username, actor);
    }

    private static void requireOwner(WorklogRepoEntity repo, String actor) {
        if (!repo.getCreatedBy().equals(actor)) {
            throw new DevMindException(ErrorCode.FORBIDDEN,
                    "只有仓主 " + repo.getCreatedBy() + " 能管理仓 " + repo.getRepoKey() + " 的成员");
        }
    }

    // ---------------- 读路径 TTL fetch（C6） ----------------

    /** 空间视图 fail-visible 提示位：后台 fetch 失败/冲突降级后置 true */
    public boolean isFetchStale(String repoKey) {
        FetchState s = fetchStates.get(repoKey);
        return s != null && s.stale();
    }

    /** 同步异常置位（自动推送失败/无可用凭证；空间视图 fail-visible 复用 C6 展示位） */
    void markSyncStale(String repoKey) {
        fetchStates.put(repoKey, new FetchState(System.currentTimeMillis(), true));
    }

    /** 同步异常复位（手动/自动推送成功） */
    void clearSyncStale(String repoKey) {
        fetchStates.put(repoKey, new FetchState(System.currentTimeMillis(), false));
    }

    /** 读操作触发：绑定远端且距上次 fetch 超 TTL → 虚拟线程后台 fetch+merge（不阻塞渲染） */
    private void maybeFetchAsync(WorklogRepoEntity repo) {
        if (repo.getRemoteUrl() == null || repo.getRemoteUrl().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        FetchState s = fetchStates.get(repo.getRepoKey());
        if (s != null && now - s.at() < fetchTtlMs) {
            return;
        }
        // 先占位节流（并发读只放一个后台任务）；终态由 backgroundFetch 覆写
        fetchStates.put(repo.getRepoKey(), new FetchState(now, s != null && s.stale()));
        Thread.ofVirtual().start(() -> backgroundFetch(repo));
    }

    /** 锁内 fetchAllRefs + mergeNoEdit；冲突/失败 abort 降级并置 stale（fail-visible，不阻塞读） */
    private void backgroundFetch(WorklogRepoEntity repo) {
        String key = repo.getRepoKey();
        boolean stale;
        try {
            String url = repo.getRemoteUrl();
            String branch = repo.getRemoteBranch() == null || repo.getRemoteBranch().isBlank()
                    ? LOCAL_BRANCH : repo.getRemoteBranch().trim();
            Optional<String> token = resolveFetchToken(repo, url);
            if (token == null) {
                // 无任何成员持该 host 的有效 PAT：跳过（保留原 stale 位，不告警）
                return;
            }
            stale = withLock(key, () -> {
                String dir = repoDir(key).toString();
                GitRemoteOps.GitResult fetch = git.fetchAllRefs(dir, url, token.orElse(null));
                if (!fetch.ok()) {
                    log.warn("工作日志仓后台 fetch 失败: repo={} err={}", key, fetch.output());
                    return true;
                }
                String originRef = "origin/" + branch;
                if (!git.refExists(dir, "refs/remotes/" + originRef)) {
                    // 远端尚无该分支（空仓/首次绑定）：无可合并，不是异常
                    return false;
                }
                // 历史不相交（远端自带初始提交）不能裸 merge；平台优先合并，远端独有文件保留
                GitRemoteOps.GitResult merge = git.hasCommonAncestor(dir, originRef)
                        ? git.mergeNoEdit(dir, originRef)
                        : git.mergeUnrelatedOurs(dir, originRef);
                if (!merge.ok()) {
                    git.mergeAbort(dir); // 冲突降级：清 MERGING，等 push 侧显式 resolve
                    log.warn("工作日志仓后台合并失败（已 abort，push 时需显式解决）: repo={} err={}",
                            key, merge.output());
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            log.warn("工作日志仓后台 fetch 异常: repo={} err={}", key, e.getMessage());
            stale = true;
        }
        fetchStates.put(key, new FetchState(System.currentTimeMillis(), stale));
    }

    /**
     * fetch/push 凭证：file:// 匿名（empty）；http/https 按 host 经 CAP-35 个人 PAT 解析——
     * 仓主优先、任一持有效 PAT 的成员兜底；均无 → null（跳过）。均在后台线程调用，无 SecurityContext。
     * （包私有：{@link WorklogRemoteBackupService} 自动推送复用同一兜底链）
     */
    Optional<String> resolveFetchToken(WorklogRepoEntity repo, String url) {
        try {
            URI uri = URI.create(url.trim());
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Optional.empty();
            }
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            RepoGitGateway gateway = gitGatewayProvider.getIfAvailable();
            if (gateway == null) {
                return null;
            }
            Optional<String> ownerToken = gateway.resolveToken(repo.getCreatedBy(), host, null);
            if (ownerToken.isPresent()) {
                return ownerToken;
            }
            for (WorklogRepoMemberEntity m : memberRepository.findByRepoId(repo.getId())) {
                if (m.getUserId().equals(repo.getCreatedBy())) {
                    continue;
                }
                Optional<String> t = gateway.resolveToken(m.getUserId(), host, null);
                if (t.isPresent()) {
                    return t;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 写路径 ----------------

    /** 写报告到操作人活动仓（AI 成稿/手动 PUT 统一入口） */
    public WriteOutcome writeReport(String username, ReportKind kind, LocalDate dateOrWeekStart,
                                    String contentMd, CommitSource source, String sessionId,
                                    Instant commitDate) {
        return writeReportTo(activeRepoFor(username), username, kind, dateOrWeekStart,
                contentMd, source, sessionId, commitDate);
    }

    /**
     * 写报告到指定仓的本人子目录：锁内写文件（字节级零规整）→ add/commit。
     * 内容一致 → 零 commit（committed=false）。commitDate 非空回填 author/committer date（迁移用）。
     */
    public WriteOutcome writeReportTo(WorklogRepoEntity repo, String username, ReportKind kind,
                                      LocalDate dateOrWeekStart, String contentMd, CommitSource source,
                                      String sessionId, Instant commitDate) {
        String subdir = subdirOf(repo, username);
        String relPath = reportRelPath(subdir, kind, dateOrWeekStart);
        return writeAndCommit(repo, username, relPath, contentMd,
                commitMessage(kind, dateOrWeekStart, source, sessionId), commitDate);
    }

    // ---------------- 通用文件原语（CAP-74 M3 条目文件化；subdir 内相对名，如 entries/2026-10-12.md） ----------------

    /** 写 subdir 内文件到操作人活动仓（条目 CRUD/导入统一入口）。 */
    public WriteOutcome writeFile(String username, String relName, String content, String commitSubject,
                                  CommitSource source, String sessionId, Instant commitDate) {
        return writeFileTo(activeRepoFor(username), username, relName, content,
                commitSubject, source, sessionId, commitDate);
    }

    /** 写 subdir 内文件到指定仓：与报告同语义（仓级锁内字节写 + commit，零变更跳过）。 */
    public WriteOutcome writeFileTo(WorklogRepoEntity repo, String username, String relName, String content,
                                    String commitSubject, CommitSource source, String sessionId,
                                    Instant commitDate) {
        String subdir = subdirOf(repo, username);
        String relPath = safeRelName(subdir, relName);
        return writeAndCommit(repo, username, relPath, content,
                messageWithTrailers(commitSubject, source, sessionId), commitDate);
    }

    /** 读 subdir 内文件（不建仓；无仓/无文件 → empty）。读路径镜像 readReport（TTL fetch 在 findActiveRepo 内）。 */
    public Optional<String> readFileIn(String username, String relName) {
        Optional<WorklogRepoEntity> repo = findActiveRepo(username);
        if (repo.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> subdir = memberRepository
                .findByRepoIdAndUserId(repo.get().getId(), username)
                .map(WorklogRepoMemberEntity::getSubdir);
        if (subdir.isEmpty()) {
            return Optional.empty();
        }
        Path file = repoDir(repo.get().getRepoKey()).resolve(safeRelName(subdir.get(), relName));
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("工作日志读取失败: " + relName, e);
        }
    }

    /** entries 目录日期扫描（升序；无仓/无目录 → 空）。from/to 闭区间可空。 */
    public List<LocalDate> listEntryDates(String username, LocalDate from, LocalDate to) {
        Optional<WorklogRepoEntity> repo = findActiveRepo(username);
        if (repo.isEmpty()) {
            return List.of();
        }
        Optional<String> subdir = memberRepository
                .findByRepoIdAndUserId(repo.get().getId(), username)
                .map(WorklogRepoMemberEntity::getSubdir);
        if (subdir.isEmpty()) {
            return List.of();
        }
        Path base = repoDir(repo.get().getRepoKey()).resolve(subdir.get()).resolve("entries");
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(base)) {
            for (Path f : stream.filter(Files::isRegularFile).toList()) {
                Matcher m = DAILY_NAME.matcher(f.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                if (from != null && date.isBefore(from)) {
                    continue;
                }
                if (to != null && date.isAfter(to)) {
                    continue;
                }
                dates.add(date);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("工作日志条目目录扫描失败: " + base, e);
        }
        dates.sort(Comparator.naturalOrder());
        return dates;
    }

    /** subdir 内相对名校验（防 `..`/绝对路径逃逸），返回仓内完整相对路径。 */
    private static String safeRelName(String subdir, String relName) {
        if (relName == null || relName.isBlank()
                || relName.contains("..") || relName.contains("\\") || relName.startsWith("/")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法仓内相对路径: " + relName);
        }
        return subdir + "/" + relName;
    }

    /** 锁内字节写 + add/commit 共用骨架（报告/条目/骨架之外的全部平台写入收口于此）。 */
    private WriteOutcome writeAndCommit(WorklogRepoEntity repo, String username, String relPath,
                                        String content, String commitMessage, Instant commitDate) {
        WriteOutcome outcome = withLock(repo.getRepoKey(), () -> {
            Path dir = repoDir(repo.getRepoKey());
            Path file = dir.resolve(relPath);
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, content, StandardCharsets.UTF_8);
                Path msgFile = dir.resolve(COMMIT_MSG_FILE);
                Files.writeString(msgFile, commitMessage, StandardCharsets.UTF_8);
                try {
                    GitIdentityProvider.GitAuthor author = resolveAuthor(username, repo.getRemoteUrl());
                    GitRemoteOps.GitResult r = git.addCommit(dir.toString(), List.of(relPath), msgFile,
                            author.name(), author.email(), commitDate);
                    if (!r.ok()) {
                        throw new DevMindException(ErrorCode.INTERNAL, "工作日志提交失败: " + r.output());
                    }
                    return new WriteOutcome(repo.getRepoKey(), relPath,
                            !r.output().contains("nothing to commit"));
                } finally {
                    Files.deleteIfExists(msgFile);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("工作日志仓写文件失败: " + relPath, e);
            }
        });
        // 写后自动推送（锁外异步，不阻塞写路径；未绑定远端/模块缺席自动跳过）
        if (outcome.committed()) {
            WorklogRemoteBackupService backup = remoteBackupProvider.getIfAvailable();
            if (backup != null) {
                backup.autoPushAsync(repo);
            }
        }
        return outcome;
    }

    // ---------------- 读路径 ----------------

    /** 报告文件是否已存在（自动生成的不覆盖判据 FR-04；不建仓） */
    public boolean reportExists(String username, ReportKind kind, LocalDate dateOrWeekStart) {
        Optional<WorklogRepoEntity> repo = findActiveRepo(username);
        if (repo.isEmpty()) {
            return false;
        }
        Optional<String> subdir = memberRepository
                .findByRepoIdAndUserId(repo.get().getId(), username)
                .map(WorklogRepoMemberEntity::getSubdir);
        return subdir.filter(s -> Files.exists(
                repoDir(repo.get().getRepoKey()).resolve(reportRelPath(s, kind, dateOrWeekStart)))).isPresent();
    }

    /** 读报告全文 + git 元信息（不建仓；无文件/无仓 → empty） */
    public Optional<ReportContent> readReport(String username, ReportKind kind, LocalDate dateOrWeekStart) {
        Optional<WorklogRepoEntity> repo = findActiveRepo(username);
        if (repo.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> subdir = memberRepository
                .findByRepoIdAndUserId(repo.get().getId(), username)
                .map(WorklogRepoMemberEntity::getSubdir);
        if (subdir.isEmpty()) {
            return Optional.empty();
        }
        String relPath = reportRelPath(subdir.get(), kind, dateOrWeekStart);
        Path file = repoDir(repo.get().getRepoKey()).resolve(relPath);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("工作日志读取失败: " + relPath, e);
        }
        List<GitRemoteOps.PathCommit> commits = git.lastCommitByPath(
                repoDir(repo.get().getRepoKey()).toString(), List.of(relPath), 50);
        GitRemoteOps.PathCommit pc = commits.isEmpty() ? null : commits.get(0);
        return Optional.of(new ReportContent(repo.get().getRepoKey(), relPath, content,
                pc == null ? null : pc.firstCommittedAt(),
                pc == null ? null : pc.committedAt(),
                pc == null ? null : pc.trailers().get("DevMind-Source"),
                pc == null ? null : pc.trailers().get("DevMind-Session")));
    }

    /**
     * 列表（读路径 FR-05）：目录扫描（文件名编码日期，倒序）+ 一次批量 git log 取时间/trailer
     * （禁 per-file fork）+ 按需读文件取 preview。from/to 闭区间可空（null=不限）。
     */
    public List<ReportSummary> listReports(String username, ReportKind kind,
                                           LocalDate from, LocalDate to, int previewChars) {
        Optional<WorklogRepoEntity> repo = findActiveRepo(username);
        if (repo.isEmpty()) {
            return List.of();
        }
        Optional<String> subdir = memberRepository
                .findByRepoIdAndUserId(repo.get().getId(), username)
                .map(WorklogRepoMemberEntity::getSubdir);
        if (subdir.isEmpty()) {
            return List.of();
        }
        Path base = repoDir(repo.get().getRepoKey())
                .resolve(subdir.get()).resolve(kind == ReportKind.DAILY ? "daily" : "weekly");
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        record Dated(String relPath, LocalDate date) {}
        List<Dated> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(base)) {
            for (Path f : stream.filter(Files::isRegularFile).toList()) {
                LocalDate date = parseFileDate(kind, f.getFileName().toString());
                if (date == null) {
                    continue;
                }
                if (from != null && date.isBefore(from)) {
                    continue;
                }
                if (to != null && date.isAfter(to)) {
                    continue;
                }
                files.add(new Dated(subdir.get() + "/" + (kind == ReportKind.DAILY ? "daily/" : "weekly/")
                        + f.getFileName().toString(), date));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("工作日志目录扫描失败: " + base, e);
        }
        files.sort(Comparator.comparing(Dated::date).reversed());
        if (files.isEmpty()) {
            return List.of();
        }
        List<String> relPaths = files.stream().map(Dated::relPath).toList();
        // 一次调用批量取全部路径的提交信息（cap 覆盖每文件多次提交）
        Map<String, GitRemoteOps.PathCommit> commits = new java.util.HashMap<>();
        for (GitRemoteOps.PathCommit pc : git.lastCommitByPath(
                repoDir(repo.get().getRepoKey()).toString(), relPaths, Math.max(500, relPaths.size() * 10))) {
            commits.put(pc.path(), pc);
        }
        List<ReportSummary> out = new ArrayList<>();
        for (Dated f : files) {
            GitRemoteOps.PathCommit pc = commits.get(f.relPath());
            out.add(new ReportSummary(f.date(), f.relPath(), preview(repoDir(repo.get().getRepoKey())
                    .resolve(f.relPath()), previewChars),
                    pc == null ? null : pc.firstCommittedAt(),
                    pc == null ? null : pc.committedAt(),
                    pc == null ? null : pc.trailers().get("DevMind-Source"),
                    pc == null ? null : pc.trailers().get("DevMind-Session")));
        }
        return out;
    }

    // ---------------- 路径与命名 ----------------

    /** 仓物理目录 = repoRoot + repoKey（本机路径不入库；key 校验防路径逃逸） */
    public Path repoDir(String repoKey) {
        if (repoKey == null || !REPO_KEY_SAFE.matcher(repoKey).matches()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法 repo_key: " + repoKey);
        }
        Path root = Path.of(properties.getRepoRoot()).toAbsolutePath().normalize();
        Path dir = root.resolve(repoKey).normalize();
        if (!dir.startsWith(root)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "repo_key 路径逃逸: " + repoKey);
        }
        return dir;
    }

    /** 报告仓内相对路径（subdir 校验防 `..` 穿越） */
    static String reportRelPath(String subdir, ReportKind kind, LocalDate dateOrWeekStart) {
        if (subdir == null || subdir.isBlank()
                || subdir.contains("..") || subdir.contains("/") || subdir.contains("\\")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法仓子目录: " + subdir);
        }
        if (kind == ReportKind.DAILY) {
            return subdir + "/daily/" + dateOrWeekStart + ".md";
        }
        return subdir + "/weekly/" + weekFileName(dateOrWeekStart) + ".md";
    }

    /** 周文件名 yyyy-Www（定稿；跨年周用 WEEK_BASED_YEAR：2025-12-29 → 2026-W01） */
    public static String weekFileName(LocalDate weekStart) {
        return String.format("%04d-W%02d",
                weekStart.get(IsoFields.WEEK_BASED_YEAR),
                weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** yyyy-Www 反解该周周一（Jan 4 恒在当年第 1 周，锚定后取周内周一） */
    static LocalDate weekStartOf(int weekBasedYear, int week) {
        return LocalDate.of(weekBasedYear, 1, 4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate parseFileDate(ReportKind kind, String fileName) {
        try {
            if (kind == ReportKind.DAILY) {
                Matcher m = DAILY_NAME.matcher(fileName);
                return m.matches() ? LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))) : null;
            }
            Matcher m = WEEKLY_NAME.matcher(fileName);
            return m.matches() ? weekStartOf(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2))) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 内部 ----------------

    private String subdirOf(WorklogRepoEntity repo, String username) {
        return memberRepository.findByRepoIdAndUserId(repo.getId(), username)
                .map(WorklogRepoMemberEntity::getSubdir)
                .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT,
                        "用户 " + username + " 不是仓 " + repo.getRepoKey() + " 的成员"));
    }

    /** 成员骨架子目录 commit（加成员/建仓共用）：daily/weekly .gitkeep + 一次性提交 */
    private void commitMemberSkeleton(Path dir, String username) {
        try {
            Path dailyKeep = dir.resolve(username + "/daily/.gitkeep");
            Path weeklyKeep = dir.resolve(username + "/weekly/.gitkeep");
            Files.createDirectories(dailyKeep.getParent());
            Files.createDirectories(weeklyKeep.getParent());
            Files.writeString(dailyKeep, "", StandardCharsets.UTF_8);
            Files.writeString(weeklyKeep, "", StandardCharsets.UTF_8);
            Path msgFile = dir.resolve(COMMIT_MSG_FILE);
            Files.writeString(msgFile, "worklog: add member " + username + "\n\nDevMind-Source: init\n",
                    StandardCharsets.UTF_8);
            try {
                GitIdentityProvider.GitAuthor author = resolveAuthor(username, null);
                GitRemoteOps.GitResult r = git.addCommit(dir.toString(), List.of(
                                username + "/daily/.gitkeep",
                                username + "/weekly/.gitkeep"),
                        msgFile, author.name(), author.email(), null);
                if (!r.ok()) {
                    throw new DevMindException(ErrorCode.INTERNAL, "成员骨架提交失败: " + r.output());
                }
            } finally {
                Files.deleteIfExists(msgFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("成员骨架写入失败: " + username, e);
        }
    }

    /** 骨架 commit：README（目录契约）+ 本人 daily/weekly .gitkeep */
    private void writeSkeleton(Path dir, String username) {
        try {
            Path readme = dir.resolve("README.md");
            Files.writeString(readme, """
                    # DevMind 工作日志仓

                    目录契约（CAP-74）：
                    - `<username>/daily/yyyy-MM-dd.md` —— 日报
                    - `<username>/weekly/yyyy-Www.md` —— 周报（周文件名按 ISO 周年，如 2026-W01）

                    本仓由平台服务端单写者收口写入；平台外编辑是合法输入（写前平台会 fetch+merge）。
                    历史与审计 = git log（commit trailer: DevMind-Source / DevMind-Session）。
                    """, StandardCharsets.UTF_8);
            Path dailyKeep = dir.resolve(username + "/daily/.gitkeep");
            Path weeklyKeep = dir.resolve(username + "/weekly/.gitkeep");
            Files.createDirectories(dailyKeep.getParent());
            Files.createDirectories(weeklyKeep.getParent());
            Files.writeString(dailyKeep, "", StandardCharsets.UTF_8);
            Files.writeString(weeklyKeep, "", StandardCharsets.UTF_8);
            Path msgFile = dir.resolve(COMMIT_MSG_FILE);
            Files.writeString(msgFile, "worklog: init " + username + "\n\nDevMind-Source: init\n",
                    StandardCharsets.UTF_8);
            try {
                GitIdentityProvider.GitAuthor author = resolveAuthor(username, null);
                GitRemoteOps.GitResult r = git.addCommit(dir.toString(), List.of(
                                "README.md",
                                username + "/daily/.gitkeep",
                                username + "/weekly/.gitkeep"),
                        msgFile, author.name(), author.email(), null);
                if (!r.ok()) {
                    throw new DevMindException(ErrorCode.INTERNAL, "工作日志仓骨架提交失败: " + r.output());
                }
            } finally {
                Files.deleteIfExists(msgFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("工作日志仓骨架写入失败", e);
        }
    }

    private static String commitMessage(ReportKind kind, LocalDate dateOrWeekStart,
                                        CommitSource source, String sessionId) {
        String subject = kind == ReportKind.DAILY
                ? "worklog: daily " + dateOrWeekStart
                : "worklog: weekly " + weekFileName(dateOrWeekStart);
        return messageWithTrailers(subject, source, sessionId);
    }

    /** commit message = 一行摘要 + trailer DevMind-Source/DevMind-Session（sessionId 空时不写）。 */
    private static String messageWithTrailers(String subject, CommitSource source, String sessionId) {
        StringBuilder msg = new StringBuilder(subject).append("\n\nDevMind-Source: ")
                .append(source.trailer()).append('\n');
        if (sessionId != null && !sessionId.isBlank()) {
            msg.append("DevMind-Session: ").append(sessionId.trim()).append('\n');
        }
        return msg.toString();
    }

    /**
     * commit 署名：GitIdentityProvider 按 remote host 解析（无远端/无绑定回退
     * displayName/username）；email 兜底 <username>@devmind.local（addCommit 要求非空）。
     */
    private GitIdentityProvider.GitAuthor resolveAuthor(String username, String remoteUrl) {
        String host = null;
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            try {
                host = URI.create(remoteUrl.trim()).getHost();
            } catch (Exception ignored) {
                // host 解析失败按无远端回退
            }
        }
        GitIdentityProvider provider = identityProvider.getIfAvailable();
        if (provider != null) {
            try {
                Optional<GitIdentityProvider.GitAuthor> resolved = provider.resolveAuthor(username, host);
                if (resolved.isPresent()) {
                    GitIdentityProvider.GitAuthor a = resolved.get();
                    String name = a.name() == null || a.name().isBlank() ? username : a.name();
                    String email = a.email() == null || a.email().isBlank()
                            ? username + "@devmind.local" : a.email();
                    return new GitIdentityProvider.GitAuthor(name, email);
                }
            } catch (Exception e) {
                log.debug("worklog commit 署名解析失败(回退默认): user={} err={}", username, e.getMessage());
            }
        }
        return new GitIdentityProvider.GitAuthor(username, username + "@devmind.local");
    }

    /** 列表 preview：文件前 N 字（截断不规整换行） */
    private static String preview(Path file, int chars) {
        if (chars <= 0) {
            return "";
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.length() <= chars ? content : content.substring(0, chars);
        } catch (IOException e) {
            return "";
        }
    }
}
