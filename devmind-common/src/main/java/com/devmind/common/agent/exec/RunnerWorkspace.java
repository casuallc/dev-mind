package com.devmind.common.agent.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * CAP-25 runner 侧托管工作区：收到带 repo 块的 launch 后负责节点本地代码生命周期——
 * 克隆缓存 → fetch 基线 → 会话 worktree。
 *
 * <p>CAP-42 布局（每用户固定工作区）：目录固定到
 * <pre>
 * &lt;workspaceRoot&gt;/&lt;projectId&gt;/&lt;workspaceOwner&gt;/main            克隆缓存（每用户每库一份）
 * &lt;workspaceRoot&gt;/&lt;projectId&gt;/&lt;workspaceOwner&gt;/work            固定 worktree（claude cwd，单库）
 * &lt;workspaceRoot&gt;/&lt;projectId&gt;/&lt;workspaceOwner&gt;/&lt;repo&gt;/main 多库缓存（CAP-31）
 * &lt;workspaceRoot&gt;/&lt;projectId&gt;/&lt;workspaceOwner&gt;/work/&lt;repo&gt; 多库子 worktree（聚合根 work/ = cwd）
 * </pre>
 * 同一 (项目, 用户) 同时只允许一个活跃工作区（占用冲突见 {@link #ensureUserWorktree}）；
 * 会话结束<b>不再 push、不再删 worktree</b>（finalizer 只上报未提交告警），收口合并
 * 由页面手动触发 {@link #finalize}；固定目录不参与 WorkspaceGc（不在 sessions/_chat
 * 扫描桶下），`.runner-pid` 孤儿进程对账回收保留（WorkspaceReconciler 第三类目录）。</p>
 *
 * <p><b>CAP-51 需求粒度工作区</b>：launch 帧带 {@code workspaceKey}（服务端下发，runner 不推导）
 * 时工作树改落 {@code worktrees/}：
 * <pre>
 * &lt;workspaceRoot&gt;/&lt;projectId&gt;/&lt;workspaceOwner&gt;/worktrees/&lt;key&gt;/         工作树（claude cwd；多库时为聚合根）
 * &lt;workspaceRoot&gt;/&lt;projectId&gt;/&lt;workspaceOwner&gt;/worktrees/&lt;key&gt;/&lt;repo&gt;/ 多库子 worktree
 * </pre>
 * key = {@code req-<需求id>}（需求内多会话共用一棵工作树与一条分支，改动自然累积）或
 * {@code sid-<会话id>}（无需求会话，用完即弃）。归属粒度从「用户」变「需求」后：占用判定
 * 由「同 (项目,用户) 是否已有工作树」降级为**磁盘残留防线**（同 key 的目录检出分支不符才报错，
 * 报错按目录/key 描述而非反查会话）；<b>收口保留工作树与分支</b>（需求可能继续开发），
 * 只在收口成功后 best-effort 把需求分支 ff 前进到新基线。key 为空 = 存量会话，走上面 CAP-42
 * 旧布局与旧语义（FR-11 兼容契约：服务端对存量会话不下发 key，不能视为错误）。</p>
 *
 * <p>CAP-34 FR-01：本类自 devmind-agent-runner 上移 common {@code agent.exec} 执行内核包
 * （该包只被 runner 引用，服务端不持有任何执行实现）。</p>
 *
 * <p><b>凭据红线</b>：token 仅存内存（{@link RepoCtx} 随会话生命周期存活），git 进程一律
 * 显式 URL 内嵌注入（仅进程参数），clone 后立即 {@code remote set-url origin <cleanUrl>}
 * 防 .git/config 残留（CAP-23 同款）；所有 git 输出经 {@link #sanitize} 后才进日志/上行帧。</p>
 *
 * <p><b>resume 幂等</b>：同 sessionId 重发 launch（服务端 resume）——固定 worktree 仍在且
 * 检出分支一致则直接复用；worktree 不在但分支还在（本地或远端 origin/）则 worktree add
 * 挂回既有分支，不丢已有提交。</p>
 */
public class RunnerWorkspace {

    private static final Logger log = LoggerFactory.getLogger(RunnerWorkspace.class);
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9._-]+");
    /** CAP-42：工作区保留目录名（GC/对账扫描桶 + 固定目录名），owner/库名禁用防撞名 */
    private static final java.util.Set<String> RESERVED_DIRS =
            java.util.Set.of("main", "sessions", "builds", "_chat", "work", "worktrees");
    /** CAP-42 旧布局工作树目录名（无 workspaceKey 的存量会话） */
    private static final String LEGACY_WORK_DIR = "work";
    /** CAP-51 需求粒度工作树根目录名（workspaceKey 非空时用） */
    private static final String WORKTREES_DIR = "worktrees";
    private static final long CLONE_TIMEOUT_SEC = 30 * 60;
    private static final long FETCH_TIMEOUT_SEC = 5 * 60;
    private static final long PUSH_TIMEOUT_SEC = 5 * 60;
    private static final long OP_TIMEOUT_SEC = 60;

    /** launch 帧 repo 块（token 仅内存）。name = CAP-31 多库子目录名（单库可为 null）。 */
    public record RepoSpec(String remoteUrl, String baseBranch, String branch, String token, String name) {
        /** 兼容构造器：单库（无 name）。 */
        public RepoSpec(String remoteUrl, String baseBranch, String branch, String token) {
            this(remoteUrl, baseBranch, branch, token, null);
        }
    }

    /** 一个会话的工作区上下文：随会话存活，结束（push+清理）后弃置。 */
    public record RepoCtx(RepoSpec spec, Path cacheDir, Path sessionDir) {
    }

    /** CAP-31 多库会话上下文：各库 RepoCtx + 聚合根（= claude cwd）。 */
    public record MultiCtx(List<RepoCtx> repos, Path aggRoot) {
    }

    private final Path workspaceRoot;
    /**
     * CAP-34 FR-04：同克隆缓存（key=cacheDir 绝对路径）的 fetch/worktree/push 互斥——
     * 并发会话同库不再踩同一缓存。锁条目不清理（项目×库数量级小，无泄漏）。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> cacheLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RunnerWorkspace(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    private java.util.concurrent.locks.ReentrantLock lockOf(Path cacheDir) {
        return cacheLocks.computeIfAbsent(cacheDir.toString(), k -> new java.util.concurrent.locks.ReentrantLock());
    }

    /** 准备会话工作区（CAP-42 旧布局 {@code work/}）：等价于 {@code prepare(..., null, spec)}。 */
    public RepoCtx prepare(String sessionId, String projectId, String workspaceOwner, RepoSpec spec) {
        return prepare(sessionId, projectId, workspaceOwner, null, spec);
    }

    /**
     * 准备会话工作区：clone（首次）→ fetch 基线 → worktree；返回 workdir 所在上下文。
     * workspaceKey 非空（CAP-51）→ {@code worktrees/<key>}，同 key 的会话共用一棵工作树。
     */
    public RepoCtx prepare(String sessionId, String projectId, String workspaceOwner,
                           String workspaceKey, RepoSpec spec) {
        requireSafeId(projectId, "projectId");
        String owner = requireOwner(workspaceOwner);
        String key = requireKey(workspaceKey);
        if (spec.branch() == null || !spec.branch().startsWith("feature/")) {
            throw new IllegalStateException("非法会话分支（必须 feature/ 前缀）: " + spec.branch());
        }
        Path userRoot = userRoot(projectId, owner);
        Path cacheDir = userRoot.resolve("main").normalize();
        Path workDir = worktreeRoot(userRoot, key);
        if (!cacheDir.startsWith(workspaceRoot) || !workDir.startsWith(workspaceRoot)) {
            throw new IllegalStateException("工作区路径越界（.. 逃逸防护）: " + projectId);
        }
        var lock = lockOf(cacheDir);
        lock.lock();
        try {
            ensureClone(cacheDir, spec);
            fetch(cacheDir, spec);
            ensureUserWorktree(cacheDir, workDir, spec, key);
        } finally {
            lock.unlock();
        }
        return new RepoCtx(spec, cacheDir, workDir);
    }

    /**
     * CAP-42 会话结束收口（best-effort）：固定 worktree 不 push 不删——仅检查未提交改动并
     * 上报告警（引导收口前先提交或丢弃）；合并+push+删 worktree 由页面手动触发 {@link #finalize}。
     */
    public void finish(RepoCtx ctx, java.util.function.Consumer<String> sink) {
        finishOne(ctx, "", sink);
    }

    /**
     * CAP-31 多库会话工作区：每库独立克隆缓存（&lt;root&gt;/&lt;projectId&gt;/&lt;owner&gt;/&lt;name&gt;/main）
     * 与固定子 worktree（&lt;root&gt;/&lt;projectId&gt;/&lt;owner&gt;/work/&lt;name&gt;），
     * 聚合根 = work/（claude cwd；CAP-42 起单库与多库同为固定布局）。
     * 中途失败：本次新建的子 worktree 显式移除（复用的不动），已抛占用冲突的不做清理。
     */
    public MultiCtx prepareMulti(String sessionId, String projectId, String workspaceOwner,
                                 List<RepoSpec> specs) {
        return prepareMulti(sessionId, projectId, workspaceOwner, null, specs);
    }

    /** CAP-51 多库版本：workspaceKey 非空 → 聚合根 {@code worktrees/<key>}（子 worktree 同下）。 */
    public MultiCtx prepareMulti(String sessionId, String projectId, String workspaceOwner,
                                 String workspaceKey, List<RepoSpec> specs) {
        requireSafeId(projectId, "projectId");
        String owner = requireOwner(workspaceOwner);
        String key = requireKey(workspaceKey);
        Path userRoot = userRoot(projectId, owner);
        Path aggRoot = worktreeRoot(userRoot, key);
        if (!aggRoot.startsWith(workspaceRoot)) {
            throw new IllegalStateException("工作区路径越界（.. 逃逸防护）: " + projectId);
        }
        List<RepoCtx> created = new ArrayList<>();
        try {
            for (RepoSpec spec : specs) {
                requireRepoName(spec.name(), true);
                if (spec.branch() == null || !spec.branch().startsWith("feature/")) {
                    throw new IllegalStateException("非法会话分支（必须 feature/ 前缀）: " + spec.branch());
                }
                Path cacheDir = userRoot.resolve(spec.name()).resolve("main").normalize();
                Path workDir = aggRoot.resolve(spec.name()).normalize();
                if (!cacheDir.startsWith(workspaceRoot) || !workDir.startsWith(aggRoot)) {
                    throw new IllegalStateException("工作区路径越界（.. 逃逸防护）: " + spec.name());
                }
                var lock = lockOf(cacheDir);
                lock.lock();
                boolean fresh;
                try {
                    ensureClone(cacheDir, spec);
                    fetch(cacheDir, spec);
                    fresh = ensureUserWorktree(cacheDir, workDir, spec, key);
                } finally {
                    lock.unlock();
                }
                if (fresh) {
                    created.add(new RepoCtx(spec, cacheDir, workDir));
                }
                log.info("多库工作区就绪: session={} repo={} dir={}", sessionId, spec.name(), workDir);
            }
        } catch (RuntimeException e) {
            // 只清理本次新建的子 worktree（复用的属既有会话状态，不能动）
            for (RepoCtx ctx : created.reversed()) {
                removeWorktreeQuietly(ctx.cacheDir(), ctx.sessionDir(), ctx.spec().token());
            }
            throw e;
        }
        List<RepoCtx> all = new ArrayList<>();
        for (RepoSpec spec : specs) {
            all.add(new RepoCtx(spec,
                    userRoot.resolve(spec.name()).resolve("main").normalize(),
                    aggRoot.resolve(spec.name()).normalize()));
        }
        return new MultiCtx(all, aggRoot);
    }

    /** CAP-31 多库结束收口（best-effort）：逐库未提交告警（不 push 不删，CAP-42 同单库口径）。 */
    public void finishMulti(MultiCtx ctx, java.util.function.Consumer<String> sink) {
        for (RepoCtx repoCtx : ctx.repos()) {
            finishOne(repoCtx, "[" + repoCtx.spec().name() + "] ", sink);
        }
    }

    /** 单库收口检查（finish/finishMulti 共用）：固定 worktree 只查未提交改动并告警。label 为上报前缀（多库带库名）。 */
    private void finishOne(RepoCtx ctx, String label, java.util.function.Consumer<String> sink) {
        var lock = lockOf(ctx.cacheDir());
        lock.lock();
        try {
            if (!Files.isDirectory(ctx.sessionDir())) {
                return;
            }
            excludePlatformPaths(ctx.cacheDir()); // 存量 worktree 补排除，避免平台物化产物造成假告警
            Result status = run(ctx.sessionDir(), OP_TIMEOUT_SEC, ctx.spec().token(), "status", "--porcelain");
            if (status.exit() == 0 && !status.output().isBlank()) {
                String dirty = dirtySummary(status.output());
                sink.accept(label + "[工作区] 工作区存在未提交改动（已保留在节点 "
                        + ctx.sessionDir() + "）：" + dirty + "，执行「收口合并到基线」前请先提交或丢弃");
                log.warn("工作区存在未提交改动: dir={} files={}", ctx.sessionDir(), dirty);
            }
        } catch (Exception e) {
            log.warn("会话工作区收口检查异常: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** owner 白名单 + 保留名校验（防撞 GC/对账扫描桶与固定目录名） */
    private static String requireOwner(String workspaceOwner) {
        if (workspaceOwner == null || !SAFE_ID.matcher(workspaceOwner).matches()
                || RESERVED_DIRS.contains(workspaceOwner)) {
            throw new IllegalStateException("非法工作区归属用户名（白名单 [a-zA-Z0-9._-]，禁用保留名 "
                    + RESERVED_DIRS + "）: " + workspaceOwner);
        }
        return workspaceOwner;
    }

    private static void requireSafeId(String id, String what) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalStateException("非法 " + what + "（白名单 [a-zA-Z0-9._-]）: " + id);
        }
    }

    /**
     * CAP-51 工作区键规范化：null/空 = 存量会话（旧布局 {@code work/}，<b>不是错误</b>——
     * 升级前建的会话服务端本就不下发 key，报错会让它们永久开不了）；非空则白名单 + 保留名校验
     * （key 是目录名，必须防 {@code ../} 逃逸与撞扫描桶）。
     */
    private static String requireKey(String workspaceKey) {
        if (workspaceKey == null || workspaceKey.isBlank()) {
            return null;
        }
        if (!SAFE_ID.matcher(workspaceKey).matches() || RESERVED_DIRS.contains(workspaceKey)) {
            throw new IllegalStateException("非法工作区键（白名单 [a-zA-Z0-9._-]，禁用保留名 "
                    + RESERVED_DIRS + "）: " + workspaceKey);
        }
        return workspaceKey;
    }

    /**
     * 工作树根目录：key 非空 → {@code <userRoot>/worktrees/<key>}（CAP-51 需求粒度）；
     * 无 key → {@code <userRoot>/work}（CAP-42 存量布局）。
     */
    private Path worktreeRoot(Path userRoot, String key) {
        return key == null ? userRoot.resolve(LEGACY_WORK_DIR).normalize()
                : userRoot.resolve(WORKTREES_DIR).resolve(key).normalize();
    }

    /**
     * 多库子目录名校验：白名单 + 全量保留名（{@code main}/{@code work}/{@code worktrees}/…）。
     * 保留名此前只挡 {@code work}，但 {@code main} 会与克隆缓存 {@code <userRoot>/<name>/main}
     * 撞成同一路径、{@code worktrees} 会与需求工作树桶撞名——两者都是静默写错地方，一并挡掉。
     */
    private static void requireRepoName(String name, boolean multi) {
        if (!multi) {
            return;
        }
        if (name == null || !SAFE_ID.matcher(name).matches() || RESERVED_DIRS.contains(name)) {
            throw new IllegalStateException("非法仓库名（白名单 [a-zA-Z0-9._-]，多库必填，禁用保留名 "
                    + RESERVED_DIRS + "）: " + name);
        }
    }

    private Path userRoot(String projectId, String owner) {
        return workspaceRoot.resolve(projectId).resolve(owner).normalize();
    }

    /**
     * CAP-36 构建工作区：&lt;workspaceRoot&gt;/&lt;projectId&gt;/builds/&lt;workspaceId&gt;，
     * detach checkout 到 commit（无分支、无 push，构建结束即弃，超龄由 {@link WorkspaceGc#sweepBuilds} 兜底）。
     * 克隆缓存复用 &lt;projectId&gt;/main（与会话链路同一缓存，fetch/worktree 同锁互斥）。
     *
     * <p><b>幂等</b>：buildDir 已在 = 同一条执行链（同 workspaceId）的后续步骤，直接复用，
     * 不再 fetch/checkout——链内步骤必须看到前步骤的产物。</p>
     *
     * @return 构建工作区目录（exec 的 cwd 基准）
     */
    public Path prepareBuild(String workspaceId, String projectId, String remoteUrl,
                             String branch, String commit, String token) {
        if (projectId == null || !SAFE_ID.matcher(projectId).matches()) {
            throw new IllegalStateException("非法 projectId（白名单 [a-zA-Z0-9._-]）: " + projectId);
        }
        if (workspaceId == null || !SAFE_ID.matcher(workspaceId).matches()) {
            throw new IllegalStateException("非法 workspaceId（白名单 [a-zA-Z0-9._-]）: " + workspaceId);
        }
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalStateException("构建工作区缺 remoteUrl");
        }
        Path cacheDir = workspaceRoot.resolve(projectId).resolve("main").normalize();
        Path buildDir = workspaceRoot.resolve(projectId).resolve("builds").resolve(workspaceId).normalize();
        if (!cacheDir.startsWith(workspaceRoot) || !buildDir.startsWith(workspaceRoot)) {
            throw new IllegalStateException("工作区路径越界（.. 逃逸防护）: " + projectId);
        }
        if (Files.isDirectory(buildDir)) {
            return buildDir; // 链内后续步骤复用
        }
        RepoSpec spec = new RepoSpec(remoteUrl, branch, branch, token);
        var lock = lockOf(cacheDir);
        lock.lock();
        try {
            ensureClone(cacheDir, spec);
            fetch(cacheDir, spec);
            String baseline = commit != null && !commit.isBlank() ? commit
                    : branch != null && !branch.isBlank() ? "FETCH_HEAD" : "HEAD";
            Result add = run(cacheDir, OP_TIMEOUT_SEC, token, "worktree", "add", "--detach",
                    buildDir.toString(), baseline);
            if (add.exit() != 0) {
                throw new IllegalStateException("git worktree add（detach " + baseline + "）失败: " + tail(add.output()));
            }
        } finally {
            lock.unlock();
        }
        log.info("构建工作区就绪: workspace={} dir={}", workspaceId, buildDir);
        return buildDir;
    }

    /**
     * CAP-36 构建 worktree 移除（best-effort）：git worktree remove --force，
     * 失败递归删目录兜底 + worktree prune 清缓存元数据。目录不在 = no-op。
     */
    public void finishBuild(String projectId, String workspaceId, String token) {
        if (projectId == null || workspaceId == null
                || !SAFE_ID.matcher(projectId).matches() || !SAFE_ID.matcher(workspaceId).matches()) {
            return;
        }
        Path cacheDir = workspaceRoot.resolve(projectId).resolve("main").normalize();
        Path buildDir = workspaceRoot.resolve(projectId).resolve("builds").resolve(workspaceId).normalize();
        if (!Files.exists(buildDir)) {
            return;
        }
        var lock = lockOf(cacheDir);
        lock.lock();
        try {
            Result rm = run(cacheDir, OP_TIMEOUT_SEC, token, "worktree", "remove", "--force", buildDir.toString());
            if (rm.exit() != 0) {
                log.warn("构建 worktree 清理失败(可人工删除 {}): {}", buildDir, tail(rm.output()));
                deleteRecursively(buildDir);
                run(cacheDir, OP_TIMEOUT_SEC, token, "worktree", "prune");
            }
        } catch (Exception e) {
            log.warn("构建 worktree 清理异常: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("目录递归删除失败: {} err={}", dir, e.getMessage());
        }
    }

    /** CAP-30 问答沙箱：&lt;workspaceRoot&gt;/_chat/&lt;sessionId&gt;（launch 帧 kind:"chat"）。
     *  幂等创建（resume 复用）；无 clone/push 语义。
     */
    public Path prepareChat(String sessionId) {
        if (sessionId == null || !SAFE_ID.matcher(sessionId).matches()) {
            throw new IllegalStateException("非法 sessionId（白名单 [a-zA-Z0-9._-]）: " + sessionId);
        }
        Path dir = workspaceRoot.resolve("_chat").resolve(sessionId).normalize();
        if (!dir.startsWith(workspaceRoot)) {
            throw new IllegalStateException("工作区路径越界（.. 逃逸防护）: " + sessionId);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("创建问答沙箱目录失败: " + dir, e);
        }
        return dir;
    }

    /**
     * CAP-41 工作日志持久工作区：&lt;worklogRoot&gt;/&lt;owner&gt;/（owner = 管控台用户名）。
     * 与代码会话根本不同——同一用户的所有 worklog 会话<b>共享同一目录</b>（日志是连续积累的
     * 工作区）：首次 git init + 骨架 commit，之后幂等复用；无 worktree、无 push、永不删除，
     * 不在 workspaceRoot 下因此天然不参与 GC/重启对账扫描。
     *
     * @param worklogRoot 根目录（调用方解析：配置 worklogRoot 或默认 {user.home}/worklog）
     */
    public Path prepareWorklog(Path worklogRoot, String owner) {
        if (owner == null || !SAFE_ID.matcher(owner).matches()) {
            throw new IllegalStateException("非法 worklog 归属用户名（白名单 [a-zA-Z0-9._-]）: " + owner);
        }
        Path base = worklogRoot.toAbsolutePath().normalize();
        Path dir = base.resolve(owner).normalize();
        if (!dir.startsWith(base)) {
            throw new IllegalStateException("worklog 工作区路径越界（.. 逃逸防护）: " + owner);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("创建 worklog 工作区目录失败: " + dir, e);
        }
        if (Files.isDirectory(dir.resolve(".git"))) {
            ensureGitignore(dir); // 存量空间幂等补写（.gitignore 为后加骨架能力，老空间没有）
            return dir; // 幂等复用：已有空间直接进
        }
        Result init = run(dir, OP_TIMEOUT_SEC, null, "init");
        if (init.exit() != 0) {
            throw new IllegalStateException("git init 失败: " + tail(init.output()));
        }
        writeSkeleton(dir);
        run(dir, OP_TIMEOUT_SEC, null, "add", "-A");
        // 骨架 commit 不依赖节点全局 git 身份配置（内联 -c 指定）
        Result commit = run(dir, OP_TIMEOUT_SEC, null,
                "-c", "user.name=devmind", "-c", "user.email=devmind@worklog.local",
                "commit", "-m", "chore: init worklog workspace");
        if (commit.exit() != 0) {
            throw new IllegalStateException("worklog 骨架 commit 失败: " + tail(commit.output()));
        }
        log.info("worklog 持久工作区已初始化: owner={} dir={}", owner, dir);
        return dir;
    }

    /**
     * CAP-41 worklog 会话结束收口（best-effort）：只检查未提交改动并上报告警——
     * 目录永不删除、无 push（纯本地 git，commit 由 agent 按 skill 约定执行）。
     */
    public void finishWorklog(Path dir, java.util.function.Consumer<String> sink) {
        if (dir == null || !Files.isDirectory(dir.resolve(".git"))) {
            return;
        }
        try {
            Result status = run(dir, OP_TIMEOUT_SEC, null, "status", "--porcelain");
            if (status.exit() == 0 && !status.output().isBlank()) {
                long n = status.output().lines().filter(l -> !l.isBlank()).count();
                sink.accept("[工作区] 工作日志空间存在 " + n + " 处未提交改动（agent 未自行 commit），"
                        + "可在节点上进入 " + dir + " 查看");
                log.warn("worklog 空间存在未提交改动: dir={} files={}", dir, n);
            }
        } catch (Exception e) {
            log.warn("worklog 收口检查异常: {} err={}", dir, e.getMessage());
        }
    }

    /**
     * CAP-41 M3 工作日志空间远端备份（手动触发）：把持久工作区 push 到用户绑定的远端仓库。
     * remote 幂等绑定 origin=cleanUrl（防 token 残留 .git/config，CAP-23 同款），
     * push HEAD:&lt;branch&gt; 并 -u 建跟踪。节点亲和单写入，冲突罕见——非快进不自动 rebase，
     * 把 sanitized 错误尾部抛给调用方引导人工处理。
     *
     * <p>URL 口径同 GitRemoteOps：仅 http/https（token 内嵌注入）与 file://（本地/测试，
     * token 忽略）；ssh 明确报错。</p>
     *
     * @param dir       持久工作区（须已 git init，见 {@link #prepareWorklog}）
     * @param remoteUrl 远端仓库 URL（不含凭证）
     * @param branch    目标分支（空 = main）
     * @param token     PAT（可空 = 匿名/file://；仅进程参数，输出经 sanitize）
     * @return exit=0 成功（含 up-to-date），否则输出尾部为错误原因
     */
    public WorklogPushOutcome pushWorklog(Path dir, String remoteUrl, String branch, String token) {
        if (dir == null || !Files.isDirectory(dir.resolve(".git"))) {
            return new WorklogPushOutcome(-1, "worklog 工作区未初始化（无 .git）: " + dir);
        }
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return new WorklogPushOutcome(-1, "未绑定远程仓库");
        }
        String cleanUrl = remoteUrl.trim();
        // scp 风格 ssh（git@host:path）URI 解析直接失败，按「不支持的协议」统一拒绝
        String scheme;
        try {
            scheme = URI.create(cleanUrl).getScheme();
        } catch (IllegalArgumentException e) {
            scheme = null;
        }
        boolean fileScheme = "file".equalsIgnoreCase(scheme);
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) && !fileScheme) {
            return new WorklogPushOutcome(-1,
                    "仅支持 http/https 远端仓库（ssh 不支持）: " + cleanUrl);
        }
        String effectiveToken = fileScheme ? null : token; // file:// 无凭证语义
        String targetBranch = (branch == null || branch.isBlank()) ? "main" : branch.trim();

        // 幂等绑定 origin=cleanUrl（不存在→add；变了→set-url）
        Result getUrl = run(dir, OP_TIMEOUT_SEC, effectiveToken, "remote", "get-url", "origin");
        if (getUrl.exit() != 0) {
            Result add = run(dir, OP_TIMEOUT_SEC, effectiveToken, "remote", "add", "origin", cleanUrl);
            if (add.exit() != 0) {
                return new WorklogPushOutcome(add.exit(), "git remote add 失败: " + tail(add.output()));
            }
        } else if (!getUrl.output().trim().equals(cleanUrl)) {
            Result set = run(dir, OP_TIMEOUT_SEC, effectiveToken, "remote", "set-url", "origin", cleanUrl);
            if (set.exit() != 0) {
                return new WorklogPushOutcome(set.exit(), "git remote set-url 失败: " + tail(set.output()));
            }
        }

        Result push = run(dir, PUSH_TIMEOUT_SEC, effectiveToken, "push", "-u",
                withToken(cleanUrl, effectiveToken), "HEAD:" + targetBranch);
        String out = tail(push.output());
        if (push.exit() != 0) {
            log.warn("worklog 远端备份 push 失败: dir={} branch={} err={}", dir, targetBranch, out);
            return new WorklogPushOutcome(push.exit(), "git push 失败: " + out);
        }
        log.info("worklog 远端备份完成: dir={} branch={}", dir, targetBranch);
        return new WorklogPushOutcome(0, out.isBlank() ? "已推送至远端分支 " + targetBranch
                : "分支 " + targetBranch + "：" + out);
    }

    /** worklog 远端备份结果：exit=0 成功（含 up-to-date），output 为摘要或错误尾部（已脱敏）。 */
    public record WorklogPushOutcome(int exit, String output) {
    }

    /** CAP-42 手动收口结果：exit=0 全部库成功；output 为逐库摘要/错误（已脱敏，多库带 [name] 前缀）。 */
    public record FinalizeOutcome(int exit, String output) {
    }

    /**
     * CAP-42 手动收口（页面触发）：对固定工作区逐库执行「合并会话分支到基线 → push 基线
     * + best-effort push 会话分支（供收口后 diff）→ 删 worktree + 删本地分支」。
     *
     * <p>合并<b>绝不动 main 缓存的检出分支</b>——在 userRoot 下的临时 detached worktree
     * （.finalize-tmp[-&lt;key&gt;][-&lt;name&gt;]，建在新 fetch 的 FETCH_HEAD 上）里 merge --no-ff 后从那里
     * push HEAD:&lt;baseBranch&gt;。合并冲突/脏工作区/push 失败 → 该库返回脱敏错误、worktree
     * 原样保留可重试；{@code discardChanges=true} 先 reset --hard + clean -fd（只清未提交脏文件，
     * 不解提交级合并冲突）。多库逐库顺序执行：成功库即时收口，失败库保留，互不阻塞。</p>
     *
     * <p>CAP-51：带 workspaceKey 时不删 worktree 与分支（需求后续会话接着用），改为 ff 前进到新基线。</p>
     */
    public FinalizeOutcome finalize(String projectId, String workspaceOwner,
                                    List<RepoSpec> specs, boolean discardChanges) {
        return finalize(projectId, workspaceOwner, specs, discardChanges, null);
    }

    /**
     * CAP-51 需求粒度收口：workspaceKey 非空 → 定位 {@code worktrees/<key>}，且收口后
     * <b>保留</b>工作树与分支（需求可能继续开发，见 {@link #finalizeOne}）。
     */
    public FinalizeOutcome finalize(String projectId, String workspaceOwner,
                                    List<RepoSpec> specs, boolean discardChanges, String workspaceKey) {
        requireSafeId(projectId, "projectId");
        String owner = requireOwner(workspaceOwner);
        String key = requireKey(workspaceKey);
        if (specs == null || specs.isEmpty()) {
            throw new IllegalStateException("收口缺少仓库描述（repos 为空）");
        }
        boolean multi = specs.size() > 1;
        Path userRoot = userRoot(projectId, owner);
        Path aggRoot = worktreeRoot(userRoot, key);
        StringBuilder summary = new StringBuilder();
        boolean allOk = true;
        for (RepoSpec spec : specs) {
            String label = spec.name() != null && !spec.name().isBlank() ? "[" + spec.name() + "] " : "";
            requireRepoName(spec.name(), multi);
            if (spec.branch() == null || !spec.branch().startsWith("feature/")) {
                throw new IllegalStateException("非法会话分支（必须 feature/ 前缀）: " + spec.branch());
            }
            Path cacheDir = multi
                    ? userRoot.resolve(spec.name()).resolve("main").normalize()
                    : userRoot.resolve("main").normalize();
            Path workDir = multi ? aggRoot.resolve(spec.name()).normalize() : aggRoot;
            var lock = lockOf(cacheDir);
            lock.lock();
            try {
                String err = finalizeOne(cacheDir, workDir, userRoot, spec, discardChanges, label,
                        summary, key);
                if (err != null) {
                    allOk = false;
                    summary.append(label).append("失败: ").append(err).append('\n');
                    log.warn("工作区收口失败: owner={} repo={} err={}", owner, spec.name(), err);
                }
            } finally {
                lock.unlock();
            }
        }
        // 多库全部收口成功：聚合根 work/ 已空则一并删除（单库的 work/ 本身就是 worktree 已被移除）。
        // CAP-51 keyed：聚合根是 worktrees/<key>，收口保留工作树，绝不清目录。
        if (allOk && multi && key == null && Files.isDirectory(aggRoot)) {
            try (var s = Files.list(aggRoot)) {
                if (s.findAny().isEmpty()) {
                    Files.deleteIfExists(aggRoot);
                }
            } catch (IOException e) {
                log.debug("聚合根清理跳过: {} err={}", aggRoot, e.getMessage());
            }
        }
        if (allOk) {
            log.info("工作区收口完成: project={} owner={} repos={}", projectId, owner, specs.size());
        }
        return new FinalizeOutcome(allOk ? 0 : 1, summary.toString().trim());
    }

    /**
     * 单库收口（finalize 逐库调用，调用方持 cacheLock）。成功返回 null 并向 summary 追加摘要；
     * 失败返回脱敏错误文案（固定 worktree 原样保留）。
     */
    private String finalizeOne(Path cacheDir, Path workDir, Path userRoot, RepoSpec spec,
                               boolean discardChanges, String label, StringBuilder summary,
                               String key) {
        if (!Files.isDirectory(cacheDir.resolve(".git"))) {
            return "克隆缓存缺失（工作区未初始化或已收口）: " + cacheDir;
        }
        if (!Files.isDirectory(workDir)) {
            // 多库部分收口后重试：worktree 与本地分支都已不在 = 该库此前已收口，幂等跳过
            // （CAP-51 keyed 收口保留工作树，走不到这里；工作树被 GC 删了才会命中）
            Result br = run(cacheDir, OP_TIMEOUT_SEC, spec.token(),
                    "rev-parse", "--verify", "--quiet", "refs/heads/" + spec.branch());
            if (br.exit() != 0) {
                summary.append(label).append("该库已收口（worktree 与分支均已移除），跳过\n");
                return null;
            }
            return "固定 worktree 不存在但分支 " + spec.branch() + " 仍在，请到节点人工核查: " + workDir;
        }
        if (spec.baseBranch() == null || spec.baseBranch().isBlank()) {
            return "缺基线分支（baseBranch），无法收口合并";
        }
        Result head = run(workDir, OP_TIMEOUT_SEC, spec.token(), "rev-parse", "--abbrev-ref", "HEAD");
        String current = head.exit() == 0 ? head.output().trim() : "";
        if (!current.equals(spec.branch())) {
            return "固定 worktree 检出分支（" + (current.isEmpty() ? "无法识别" : current)
                    + "）与会话分支（" + spec.branch() + "）不一致，请到节点人工核查";
        }
        // 从未 resume 过的存量 worktree：排除规则是后加的，这里补一次（幂等），
        // 否则仅由平台物化产物造成的「假脏」（?? .devmind/ 等）也会挡住收口
        excludePlatformPaths(cacheDir);
        Result status = run(workDir, OP_TIMEOUT_SEC, spec.token(), "status", "--porcelain");
        if (status.exit() != 0) {
            return "git status 失败: " + tail(status.output());
        }
        if (!status.output().isBlank()) {
            if (!discardChanges) {
                return "工作区存在未提交改动（已保留）：" + dirtySummary(status.output())
                        + "，请先 resume 会话提交，或勾选「丢弃未提交改动」重试";
            }
            Result reset = run(workDir, OP_TIMEOUT_SEC, spec.token(), "reset", "--hard");
            if (reset.exit() != 0) {
                return "reset --hard 失败: " + tail(reset.output());
            }
            Result clean = run(workDir, OP_TIMEOUT_SEC, spec.token(), "clean", "-fd");
            if (clean.exit() != 0) {
                return "clean -fd 失败: " + tail(clean.output());
            }
            summary.append(label).append("已丢弃未提交改动\n");
        }
        try {
            fetch(cacheDir, spec);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        // 合并放临时 detached worktree（userRoot/.finalize-tmp[-<key>][-<name>]），不动 main 缓存检出分支
        Path tmp = userRoot.resolve(".finalize-tmp"
                + (key != null ? "-" + key : "")
                + (spec.name() != null ? "-" + spec.name() : "")).normalize();
        String baseSha = ""; // 本轮推上去的新基线提交号（临时 worktree 拆掉后仍要用）
        try {
            removeWorktreeQuietly(cacheDir, tmp, spec.token()); // 防上次失败残留
            deleteRecursively(tmp);
            Result add = run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "add", "--detach",
                    tmp.toString(), "FETCH_HEAD");
            if (add.exit() != 0) {
                return "临时合并工作区创建失败: " + tail(add.output());
            }
            Result merge = run(tmp, OP_TIMEOUT_SEC, spec.token(),
                    "-c", "user.name=devmind", "-c", "user.email=devmind@runner.local",
                    "merge", "--no-ff", "-m", "merge: 会话分支 " + spec.branch() + " 收口", spec.branch());
            if (merge.exit() != 0) {
                run(tmp, OP_TIMEOUT_SEC, spec.token(), "merge", "--abort");
                return "合并到基线存在冲突（工作区已保留）: " + tail(merge.output())
                        + "。可 resume 会话让 agent rebase 解冲突后再收口，或到节点手工处理";
            }
            Result push = run(tmp, PUSH_TIMEOUT_SEC, spec.token(), "push",
                    withToken(spec.remoteUrl(), spec.token()), "HEAD:" + spec.baseBranch());
            if (push.exit() != 0) {
                return "push 基线分支失败（如提示非快进 = 基线已被他人推进，请重试收口）: " + tail(push.output());
            }
            // 新基线提交号（tmp HEAD 就是刚推上去的合并提交）：供 CAP-51 收口后把保留的
            // 工作树 ff 前进到它。不用 FETCH_HEAD——那是<b>每个 worktree 各自一份</b>的引用，
            // 在缓存库 fetch 出来、到工作树里读不到（.git/worktrees/<n>/FETCH_HEAD 不存在）。
            Result baseHead = run(tmp, OP_TIMEOUT_SEC, spec.token(), "rev-parse", "HEAD");
            baseSha = baseHead.exit() == 0 ? baseHead.output().trim() : "";
            summary.append(label).append("已合并 ").append(spec.branch())
                    .append(" → ").append(spec.baseBranch()).append(" 并推送基线\n");
        } finally {
            removeWorktreeQuietly(cacheDir, tmp, spec.token());
            if (Files.exists(tmp)) {
                deleteRecursively(tmp);
                run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "prune");
            }
        }
        // best-effort push 会话分支（供收口后 diff 查看；失败不阻断收口）
        Result pushBranch = run(cacheDir, PUSH_TIMEOUT_SEC, spec.token(), "push",
                withToken(spec.remoteUrl(), spec.token()),
                "refs/heads/" + spec.branch() + ":refs/heads/" + spec.branch());
        if (pushBranch.exit() != 0) {
            log.warn("会话分支推送失败（仅影响收口后 diff 查看）: branch={} err={}", spec.branch(),
                    tail(pushBranch.output()));
            summary.append(label).append("会话分支推送失败（仅影响收口后 diff 查看）: ")
                    .append(tail(pushBranch.output())).append('\n');
        }
        if (key != null) {
            // CAP-51：收口<b>保留</b>工作树与分支——需求可能还要继续开发，保留才能让后续会话
            // 接着用（同需求内改动天然累积）。但工作树仍停在收口前的基线上，必须 ff 前进到
            // 刚推上去的合并提交，否则下次收口会把同一批提交再合一遍、下次会话看到旧基线。
            advanceToBaseline(workDir, spec, baseSha, label, summary);
            return null;
        }
        // 删固定 worktree + 本地会话分支（best-effort：目录删不掉则兜底递归删 + prune）
        Result rm = run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "remove", "--force",
                workDir.toString());
        if (rm.exit() != 0) {
            log.warn("收口移除 worktree 失败，递归删兜底: {} err={}", workDir, tail(rm.output()));
            deleteRecursively(workDir);
            run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "prune");
        }
        run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "branch", "-D", spec.branch());
        summary.append(label).append("已删除固定 worktree 与本地分支 ").append(spec.branch()).append('\n');
        return null;
    }

    /**
     * CAP-51 收口后把工作树检出的需求分支前进到新基线（best-effort）。必须 {@code --ff-only}：
     * 工作树自身的提交是刚生成的合并提交的父提交，正常必能快进；快进不了（工作树有未提交改动
     * 或已分叉）说明现场有人动过，跳过并告警——<b>不影响收口结果</b>（基线已推进、分支已 push），
     * 只是该工作树下一次会话/收口看到旧基线，人工处理即可。
     *
     * <p>目标是 {@code baseSha}（本轮临时 worktree 推上基线的那个提交），对象已在同一克隆的
     * 对象库里，<b>不需要也不该再 fetch</b>：FETCH_HEAD 是每个 worktree 各自的引用，缓存库里
     * fetch 的 FETCH_HEAD 在工作树里根本不存在（2026-09-21 实测报
     * {@code could not open '.../.git/worktrees/<n>/FETCH_HEAD'}）。</p>
     */
    private void advanceToBaseline(Path workDir, RepoSpec spec, String baseSha, String label,
                                   StringBuilder summary) {
        if (baseSha == null || baseSha.isBlank()) {
            summary.append(label).append("工作树已保留；前进到新基线跳过（未取到基线提交号）\n");
            return;
        }
        Result merge = run(workDir, OP_TIMEOUT_SEC, spec.token(), "merge", "--ff-only", baseSha);
        if (merge.exit() != 0) {
            log.warn("收口后工作树前进失败（保留原样）: dir={} err={}", workDir, tail(merge.output()));
            summary.append(label).append("工作树已保留，但前进到新基线失败（工作树有未提交改动或已分叉，")
                    .append("resume 会话后人工处理）: ").append(tail(merge.output())).append('\n');
            return;
        }
        summary.append(label).append("工作树已保留并前进到基线 ").append(spec.baseBranch()).append('\n');
    }

    /** CAP-42 固定工作区释放结果：exit=0 全部库成功；output 为逐库摘要/错误（已脱敏，多库带 [name] 前缀）。 */
    public record ReleaseOutcome(int exit, String output) {
    }

    /**
     * CAP-42 删除会话时释放固定工作区：逐库执行「丢弃未提交改动（worktree remove --force）
     * → 删 worktree → 删本地会话分支」——<b>不合并不 push</b>（删除是丢弃语义，不能把会话分支
     * 合入基线，也不该在删除时改动远端）。
     *
     * <p>为什么必须有这条链路：固定工作区按 (项目, 用户) 唯一占用，占用判定只看<b>磁盘上
     * worktree 的检出分支</b>（{@link #ensureUserWorktree}）。会话记录被删后目录若还在，新会话
     * launch 必失败、而报错引导的「收口」入口又随会话记录一起消失——该 (项目,用户) 永久锁死
     * （2026-09-21 admq-manager/admin 实事故）。</p>
     *
     * <p>CAP-51：带 workspaceKey 时定位 {@code worktrees/<key>} 整块回收——需求记录被删后，
     * 该需求的目录与分支同样不能留（FR-06 释放触发点）；keyed 布局下同目录不再跨需求复用，
     * 所以一个需求的残留只影响它自己。</p>
     */
    public ReleaseOutcome release(String projectId, String workspaceOwner, List<RepoSpec> specs) {
        return release(projectId, workspaceOwner, specs, null);
    }

    /**
     * CAP-51 需求粒度释放：workspaceKey 非空 → 定位 {@code worktrees/<key>} 并整块回收
     * （语义不变：丢弃未提交改动 + 删 worktree + 删本地分支，<b>不合并不 push</b>）。
     */
    public ReleaseOutcome release(String projectId, String workspaceOwner, List<RepoSpec> specs,
                                  String workspaceKey) {
        requireSafeId(projectId, "projectId");
        String owner = requireOwner(workspaceOwner);
        String key = requireKey(workspaceKey);
        if (specs == null || specs.isEmpty()) {
            throw new IllegalStateException("释放工作区缺少仓库描述（repos 为空）");
        }
        boolean multi = specs.size() > 1;
        Path userRoot = userRoot(projectId, owner);
        Path aggRoot = worktreeRoot(userRoot, key);
        StringBuilder summary = new StringBuilder();
        boolean allOk = true;
        for (RepoSpec spec : specs) {
            String label = spec.name() != null && !spec.name().isBlank() ? "[" + spec.name() + "] " : "";
            requireRepoName(spec.name(), multi);
            if (spec.branch() == null || !spec.branch().startsWith("feature/")) {
                throw new IllegalStateException("非法会话分支（必须 feature/ 前缀）: " + spec.branch());
            }
            Path cacheDir = multi
                    ? userRoot.resolve(spec.name()).resolve("main").normalize()
                    : userRoot.resolve("main").normalize();
            Path workDir = multi ? aggRoot.resolve(spec.name()).normalize() : aggRoot;
            var lock = lockOf(cacheDir);
            lock.lock();
            try {
                String err = releaseOne(cacheDir, workDir, spec, label, summary);
                if (err != null) {
                    allOk = false;
                    summary.append(label).append("失败: ").append(err).append('\n');
                    log.warn("固定工作区释放失败: owner={} repo={} err={}", owner, spec.name(), err);
                }
            } finally {
                lock.unlock();
            }
        }
        // 多库全部释放成功：聚合根 work/ 已空则一并删除（单库的 work/ 本身就是 worktree 已被移除）。
        // CAP-51 keyed：多库释放后 worktrees/<key> 也是空壳，删掉（释放是整块回收语义）。
        if (allOk && multi && Files.isDirectory(aggRoot)) {
            try (var s = Files.list(aggRoot)) {
                if (s.findAny().isEmpty()) {
                    Files.deleteIfExists(aggRoot);
                }
            } catch (IOException e) {
                log.debug("聚合根清理跳过: {} err={}", aggRoot, e.getMessage());
            }
        }
        if (allOk) {
            log.info("固定工作区已释放: project={} owner={} repos={}", projectId, owner, specs.size());
        }
        return new ReleaseOutcome(allOk ? 0 : 1, summary.toString().trim());
    }

    /**
     * 单库释放（release 逐库调用，调用方持 cacheLock）。成功返回 null 并向 summary 追加摘要；
     * 失败返回脱敏错误文案（固定 worktree 保留）。<b>幂等</b>：worktree 与分支都不在 = 已释放，跳过；
     * 目录不在但分支残留 = 一并清理（收口/释放中断留下的半成品）。
     */
    private String releaseOne(Path cacheDir, Path workDir, RepoSpec spec, String label,
                              StringBuilder summary) {
        if (!Files.isDirectory(cacheDir.resolve(".git"))) {
            return "克隆缓存缺失（工作区未初始化或已释放）: " + cacheDir;
        }
        if (!Files.isDirectory(workDir)) {
            Result br = run(cacheDir, OP_TIMEOUT_SEC, spec.token(),
                    "rev-parse", "--verify", "--quiet", "refs/heads/" + spec.branch());
            if (br.exit() != 0) {
                summary.append(label).append("该库工作区已释放，跳过\n");
                return null;
            }
            run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "branch", "-D", spec.branch());
            summary.append(label).append("worktree 已不在，残留本地分支已清理: ")
                    .append(spec.branch()).append('\n');
            return null;
        }
        // 丢弃前先把现场报告出来（删完就看不到了）：未提交文件数 + 仅存在于本分支的提交数
        Result status = run(workDir, OP_TIMEOUT_SEC, spec.token(), "status", "--porcelain");
        long dirty = status.exit() == 0 && !status.output().isBlank()
                ? status.output().lines().count() : 0;
        long onlyLocal = countLocalOnlyCommits(workDir, spec);
        Result rm = run(cacheDir, OP_TIMEOUT_SEC, spec.token(),
                "worktree", "remove", "--force", workDir.toString());
        if (rm.exit() != 0) {
            log.warn("释放移除 worktree 失败，递归删兜底: {} err={}", workDir, tail(rm.output()));
            deleteRecursively(workDir);
            run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "prune");
            if (Files.exists(workDir)) {
                return "固定 worktree 删除失败（目录可能被占用，请到节点手工删除）: " + workDir;
            }
        }
        Result br = run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "branch", "-D", spec.branch());
        if (br.exit() != 0) {
            // 目录已删，残留分支下次释放/收口会再清；不计失败（会话记录已无法再指向它）
            log.warn("释放删除本地会话分支失败: {} err={}", spec.branch(), tail(br.output()));
        }
        summary.append(label).append("已释放固定 worktree");
        if (dirty > 0) {
            summary.append("（丢弃未提交文件 ").append(dirty).append(" 个）");
        }
        if (onlyLocal > 0) {
            summary.append("（丢弃仅存于本地的提交 ").append(onlyLocal).append(" 个）");
        }
        summary.append('\n');
        return null;
    }

    /**
     * 仅存于本地、远端任何 ref 上都没有的提交数——释放前告知用户到底丢了多少活（只有这些
     * 提交会随目录删除彻底消失）。<b>不用 {@code --exclude=<会话分支> --all}</b>：{@code --all}
     * 会把 HEAD 单独算进来，exclude 掉分支 ref 也去不掉 HEAD 自身，实测恒等于全量提交数
     * （2026-09-21 单元测试踩中）。命令失败返回 0（计数只是提示，绝不因它挡住释放）。
     */
    private long countLocalOnlyCommits(Path workDir, RepoSpec spec) {
        Result r = run(workDir, OP_TIMEOUT_SEC, spec.token(), "rev-list", "--count", "HEAD",
                "--not", "--remotes");
        if (r.exit() != 0) {
            return 0;
        }
        try {
            return Long.parseLong(r.output().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** worklog 空间骨架：目录契约说明 + daily/weekly/entries 占位。 */
    /** worklog 空间 .gitignore：平台托管文件（上下文装配/物化设置/回传产物）不入库，
     *  否则 agent 按 README「git add -A」会把它提交并随远端备份推上公网仓库。
     *  CLAUDE.md 是 CAP-34 旧注入落点（现为 {@link ContextMaterializer#INJECTION_FILE}），
     *  存量空间可能已提交过，规则保留。 */
    private static final String WORKLOG_GITIGNORE = String.join("\n",
            "# Dev-Mind 平台托管文件（每次会话 launch 重新物化，勿提交）",
            ContextMaterializer.INJECTION_FILE,
            "CLAUDE.md",
            ".claude/",
            ".devmind/") + "\n";

    private static void writeSkeleton(Path dir) {
        String readme = """
                # 工作日志空间

                本目录由 DevMind runner 托管（本地 git 维护；远端备份由管控台「推送远端」统一执行，agent 不自行 push）。

                ## 目录契约
                - `daily/yyyy-MM-dd.md`   日报（一天一份）
                - `weekly/yyyy-Www.md`    周报（ISO 周，如 2026-W37）
                - `entries/`              工作条目素材（可选）
                - `.devmind/output/`      会话成稿回传目录（写这里的内容会自动回传管控台落库）

                改动完成后请 `git add -A && git commit`。
                """;
        try {
            Files.writeString(dir.resolve("README.md"), readme, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(".gitignore"), WORKLOG_GITIGNORE, StandardCharsets.UTF_8);
            for (String sub : new String[]{"daily", "weekly", "entries"}) {
                Path d = dir.resolve(sub);
                Files.createDirectories(d);
                Files.writeString(d.resolve(".gitkeep"), "", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写 worklog 骨架文件失败: " + dir, e);
        }
    }

    /**
     * 存量 worklog 空间补写 .gitignore 缺失行（已有行与用户自行加过的规则一律不动），
     * 并只对 .gitignore 做一次平台代提交：该文件在骨架 commit 里是被跟踪的，只改不提交
     * 会让空间恒脏（agent 下次「git add -A」又会把它连同脏状态一起带走）。提交失败只告警
     * ——忽略规则读的是工作区文件，即使没提交也照样生效。
     */
    private void ensureGitignore(Path dir) {
        Path gitignore = dir.resolve(".gitignore");
        try {
            String content = Files.isRegularFile(gitignore)
                    ? Files.readString(gitignore, StandardCharsets.UTF_8) : "";
            var existing = content.lines().map(String::strip).toList();
            StringBuilder missing = new StringBuilder();
            for (String line : WORKLOG_GITIGNORE.lines().map(String::strip).toList()) {
                if (!line.isEmpty() && !existing.contains(line)) {
                    missing.append(line).append('\n');
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            Files.writeString(gitignore,
                    content + (content.isEmpty() || content.endsWith("\n") ? "" : "\n") + missing,
                    StandardCharsets.UTF_8);
            log.info("worklog 存量空间已补写 .gitignore 缺失行: dir={}", dir);
            if (run(dir, OP_TIMEOUT_SEC, null, "add", ".gitignore").exit() == 0) {
                Result commit = run(dir, OP_TIMEOUT_SEC, null,
                        "-c", "user.name=devmind", "-c", "user.email=devmind@worklog.local",
                        "commit", "-m", "chore: 补平台托管 .gitignore 规则", "--", ".gitignore");
                if (commit.exit() != 0) {
                    log.warn("worklog .gitignore 代提交失败（规则仍生效，可手工提交）: {}", tail(commit.output()));
                }
            }
        } catch (IOException e) {
            // best-effort：迁移失败不该让用户开不了会话（忽略规则读工作区文件，缺行只影响增量补写）
            log.warn("补写 worklog .gitignore 失败（可人工补行）: dir={} err={}", dir, e.getMessage());
        }
    }

    /** CAP-30 问答结束收口（best-effort）：递归删除沙箱目录。 */
    public void cleanChat(String sessionId, java.util.function.Consumer<String> sink) {
        Path dir = workspaceRoot.resolve("_chat").resolve(sessionId).normalize();
        if (!dir.startsWith(workspaceRoot) || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            sink.accept("[工作区] 问答沙箱已清理: " + dir);
        } catch (IOException e) {
            log.warn("问答沙箱清理失败(可人工删除 {}): {}", dir, e.getMessage());
            sink.accept("[工作区] 问答沙箱清理失败（可人工删除 " + dir + "）: " + e.getMessage());
        }
    }

    /** 克隆缓存就位：已有 .git 直接复用；否则 clone（token 内嵌）+ 立即清 origin URL 残留 */private void ensureClone(Path cacheDir, RepoSpec spec) {
        if (Files.isDirectory(cacheDir.resolve(".git"))) {
            return;
        }
        if (Files.isDirectory(cacheDir)) {
            try (var s = Files.list(cacheDir)) {
                if (s.findAny().isPresent()) {
                    throw new IllegalStateException("克隆缓存目录已存在且非 git 仓库: " + cacheDir);
                }
            } catch (IOException e) {
                throw new IllegalStateException("克隆缓存目录不可读: " + cacheDir, e);
            }
        }
        try {
            Files.createDirectories(cacheDir.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("创建工作区目录失败: " + cacheDir.getParent(), e);
        }
        log.info("首次会话，克隆仓库到节点工作区: {}", cacheDir);
        Result clone = run(cacheDir.getParent(), CLONE_TIMEOUT_SEC, spec.token(),
                "clone", withToken(spec.remoteUrl(), spec.token()), cacheDir.toString());
        if (clone.exit() != 0) {
            throw new IllegalStateException("git clone 失败: " + tail(clone.output()));
        }
        if (spec.token() != null && !spec.token().isBlank()) {
            // 防 token 残留 .git/config（CAP-23 同款）；后续 fetch/push 显式注入，不依赖 origin 凭据
            run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "remote", "set-url", "origin", spec.remoteUrl());
        }
    }

    private void fetch(Path cacheDir, RepoSpec spec) {
        List<String> args = new ArrayList<>(List.of("fetch", withToken(spec.remoteUrl(), spec.token())));
        if (spec.baseBranch() != null && !spec.baseBranch().isBlank()) {
            args.add(spec.baseBranch());
        }
        Result r = run(cacheDir, FETCH_TIMEOUT_SEC, spec.token(), args.toArray(new String[0]));
        if (r.exit() != 0) {
            throw new IllegalStateException("git fetch 失败: " + tail(r.output()));
        }
    }

    /**
     * CAP-42/CAP-51 worktree 占用/幂等判定：
     * 1. workDir 存在 → 检出分支 == 会话分支 = resume 复用（返回 false）；分支不符 = 占用冲突
     *    （CAP-42 旧布局错误带占用分支名引导收口；CAP-51 keyed 布局按目录/key 描述——
     *    占用判定已上移到服务端的「同需求进行中会话」预检，这里只是磁盘残留的最终防线）；
     * 2. workDir 不存在 → 本地分支在 = 挂回；本地无但 origin/&lt;branch&gt; 在（老会话已 push，
     *    新克隆缓存迁移）→ 从远端分支建本地分支挂回防分叉；都没有 = 从基线 FETCH_HEAD 新建。
     *
     * @param key workspaceKey（非空 = CAP-51 需求粒度布局，仅供错误文案与父目录创建使用）
     * @return true = 本次新建（prepareMulti 失败回滚时只清理新建的，复用的不动）
     */
    private boolean ensureUserWorktree(Path cacheDir, Path workDir, RepoSpec spec, String key) {
        if (Files.isDirectory(workDir)) {
            Result head = run(workDir, OP_TIMEOUT_SEC, spec.token(), "rev-parse", "--abbrev-ref", "HEAD");
            String current = head.exit() == 0 ? head.output().trim() : "";
            if (current.equals(spec.branch())) {
                excludePlatformPaths(cacheDir); // 修复前遗留的 worktree 补齐排除（幂等）
                log.info("worktree 已存在且分支一致（resume 复用）: {}", workDir);
                return false;
            }
            String found = current.isEmpty() ? "，无法识别检出分支" : "，当前分支 " + current;
            if (key != null) {
                // 需求粒度下同 key 目录同一时刻只应有本需求的分支；不符 = 磁盘残留（历史布局/人工改动）
                throw new IllegalStateException("工作区目录 " + workDir + " 已被占用" + found
                        + "，与本次会话分支 " + spec.branch() + " 不一致（工作区键 " + key
                        + "）。请先在对应需求的「收口合并到基线」完成收口，或到节点人工核查该目录");
            }
            throw new IllegalStateException("该用户在本项目已有占用中的工作区（目录 " + workDir + found
                    + "）。请先在会话 " + occupantOf(current)
                    + " 的「更多 → 收口合并到基线」完成收口（或由其本人/管理员执行），再开新会话");
        }
        try {
            // CAP-51：workDir 的父目录（worktrees/ 或 worktrees/<key>）由本平台创建，
            // 不能让 git worktree add 的建目录行为成为隐式依赖
            Files.createDirectories(workDir.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("创建工作区父目录失败: " + workDir.getParent(), e);
        }
        Result verify = run(cacheDir, OP_TIMEOUT_SEC, spec.token(),
                "rev-parse", "--verify", "--quiet", "refs/heads/" + spec.branch());
        Result add;
        if (verify.exit() == 0) {
            add = run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "add",
                    workDir.toString(), spec.branch());
        } else if (run(cacheDir, OP_TIMEOUT_SEC, spec.token(),
                "rev-parse", "--verify", "--quiet", "refs/remotes/origin/" + spec.branch()).exit() == 0) {
            // 老会话 resume 迁移：分支已 push 远端、新克隆缓存本地无 → 从 origin/ 挂回防分叉
            add = run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "add",
                    "-b", spec.branch(), workDir.toString(), "origin/" + spec.branch());
        } else {
            String baseline = spec.baseBranch() != null && !spec.baseBranch().isBlank()
                    ? "FETCH_HEAD" : "HEAD";
            add = run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "add",
                    "-b", spec.branch(), workDir.toString(), baseline);
        }
        if (add.exit() != 0) {
            throw new IllegalStateException("git worktree add 失败: " + tail(add.output()));
        }
        excludePlatformPaths(cacheDir);
        log.info("worktree 就绪: {} (branch {})", workDir, spec.branch());
        return true;
    }

    /** 脏文件清单最多列出的个数（收口被挡住时告诉用户到底哪些文件挡的，多则只报总数）。 */
    private static final int DIRTY_PREVIEW = 3;

    /** `git status --porcelain` 输出 → 「a.txt、b.txt 等 5 处」（前 3 列是状态码，剔掉取路径）。 */
    private static String dirtySummary(String porcelain) {
        List<String> files = porcelain.lines()
                .map(String::strip)
                .filter(l -> !l.isBlank())
                .map(l -> l.length() > 3 ? l.substring(3).strip() : l)
                .toList();
        String head = String.join("、", files.subList(0, Math.min(DIRTY_PREVIEW, files.size())));
        return files.size() > DIRTY_PREVIEW ? head + " 等 " + files.size() + " 处" : head;
    }

    /**
     * 平台托管路径（会话进程 pid 文件 + 上下文物化产物）必须排除出版本控制，否则：
     * <ol>
     *   <li>agent「git add -A」会把它们提交进会话分支，收口后合入基线造成污染；</li>
     *   <li>单库会话 worktree 根就是仓库工作区，物化产物落在那里 → 工作区恒脏，收口被
     *       「未提交改动」挡住（干净会话也收不了口，CAP-42 事故补）；会话结束删 pid 文件
     *       同理（CAP-42 E2E 实测踩中）。</li>
     * </ol>
     * 走克隆缓存的 {@code .git/info/exclude}（全 worktree 共享，不改仓库自带 .gitignore）；
     * 用精确路径而非整目录 {@code .claude/}（仓库可能自己要跟踪 .claude 下的东西）。
     * 幂等、best-effort，失败只告警。
     */
    private static final List<String> PLATFORM_EXCLUDES = List.of(
            "/" + WorkspaceReconciler.PID_FILE,
            ContextMaterializer.INJECTION_FILE,
            ".devmind/",
            ".claude/settings.local.json",
            ".claude/skills/");

    private void excludePlatformPaths(Path cacheDir) {
        try {
            Path exclude = cacheDir.resolve(".git/info/exclude");
            String content = Files.isRegularFile(exclude)
                    ? Files.readString(exclude, StandardCharsets.UTF_8) : "";
            var existing = content.lines().map(String::strip).toList();
            StringBuilder add = new StringBuilder();
            for (String pattern : PLATFORM_EXCLUDES) {
                if (!existing.contains(pattern)) {
                    add.append(pattern).append('\n');
                }
            }
            if (add.isEmpty()) {
                return;
            }
            Files.createDirectories(exclude.getParent());
            Files.writeString(exclude,
                    content + (content.isEmpty() || content.endsWith("\n") ? "" : "\n") + add,
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("写入 info/exclude 失败（平台路径将污染 git status）: {}", e.getMessage());
        }
    }

    /** 占用分支名 → 占用会话 id（feature/&lt;sid&gt; 约定；无法识别时原样返回） */
    private static String occupantOf(String branch) {
        return branch != null && branch.startsWith("feature/") && branch.length() > "feature/".length()
                ? branch.substring("feature/".length()) : String.valueOf(branch);
    }

    /** best-effort 移除 worktree（prepareMulti 失败回滚专用；异常只记日志） */
    private void removeWorktreeQuietly(Path cacheDir, Path workDir, String token) {
        try {
            Result rm = run(cacheDir, OP_TIMEOUT_SEC, token,
                    "worktree", "remove", "--force", workDir.toString());
            if (rm.exit() != 0) {
                log.warn("回滚移除 worktree 失败(可人工删除 {}): {}", workDir, tail(rm.output()));
            }
        } catch (Exception e) {
            log.warn("回滚移除 worktree 异常: {}", e.getMessage());
        }
    }

    /** HTTPS URL 内嵌 PAT（仅进程参数；GitLab 约定 oauth2 用户名，GitHub 接受任意用户名） */
    private static String withToken(String url, String token) {
        if (token == null || token.isBlank()) {
            return url;
        }
        URI uri = URI.create(url.trim());
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://oauth2:").append(token).append('@').append(uri.getHost());
        if (uri.getPort() > 0) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        return sb.toString();
    }

    private record Result(int exit, String output) {
    }

    /** 跑 git 进程，输出合并捕获并脱敏（token 明文 + URL 编码形态 → ***）后才允许外溢 */
    private Result run(Path cwd, long timeoutSec, String token, String... args) {
        List<String> cmd = buildCmd(cwd, args);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // 读输出与等待分离，防缓冲满死锁
            var outFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "git 命令超时（" + timeoutSec + "s）已终止");
            }
            return new Result(p.exitValue(), sanitize(outFuture.join(), token));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "git 命令被中断");
        } catch (IOException e) {
            return new Result(-1, "git 命令执行失败: " + e.getMessage());
        }
    }

    /**
     * 组 git 命令行。CAP-43：NodeProxy holder 命中 git scope → {@code git} 后插
     * {@code -c http.proxy=<url>}（全局选项必须在 -C 与子命令之前；进程级注入不落
     * repo/global 配置，单一事实源在服务端节点配置）。package-private 供单测断言。
     */
    static List<String> buildCmd(Path cwd, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        String proxy = NodeProxy.urlFor("git");
        if (proxy != null) {
            cmd.add("-c");
            cmd.add("http.proxy=" + proxy);
        }
        cmd.add("-C");
        cmd.add(cwd.toString());
        cmd.addAll(List.of(args));
        return cmd;
    }

    /** 输出脱敏（token 明文 + URL 编码形态 → ***）；CAP-36 exec 日志帧回流前同样必须过此。 */
    public static String sanitize(String s, String token) {
        if (s == null || token == null || token.isEmpty()) {
            return s;
        }
        String out = s.replace(token, "***");
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        if (!encoded.equals(token)) {
            out = out.replace(encoded, "***");
        }
        return out;
    }

    private static String tail(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= 300 ? t : "…" + t.substring(t.length() - 300);
    }
}
