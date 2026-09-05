package com.devmind.agent.runner;

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
 * 克隆缓存（&lt;workspaceRoot&gt;/&lt;projectId&gt;/main，首会话 clone）→ fetch 基线 →
 * 每会话独立 worktree（sessions/&lt;sessionId&gt;，分支由服务端下发）→ 结束 push + 清理。
 *
 * <p><b>凭据红线</b>：token 仅存内存（{@link RepoCtx} 随会话生命周期存活），git 进程一律
 * 显式 URL 内嵌注入（仅进程参数），clone 后立即 {@code remote set-url origin <cleanUrl>}
 * 防 .git/config 残留（CAP-23 同款）；所有 git 输出经 {@link #sanitize} 后才进日志/上行帧。</p>
 *
 * <p><b>resume 幂等</b>：同 sessionId 重发 launch（服务端 resume）——会话目录仍在则直接复用；
 * 目录已清理但分支还在（上次会话结束 push 后保留了分支）则 worktree add 挂回既有分支，
 * 不丢已有提交。</p>
 */
public class RunnerWorkspace {

    private static final Logger log = LoggerFactory.getLogger(RunnerWorkspace.class);
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9._-]+");
    private static final long CLONE_TIMEOUT_SEC = 30 * 60;
    private static final long FETCH_TIMEOUT_SEC = 5 * 60;
    private static final long PUSH_TIMEOUT_SEC = 5 * 60;
    private static final long OP_TIMEOUT_SEC = 60;

    /** launch 帧 repo 块（token 仅内存）。 */
    public record RepoSpec(String remoteUrl, String baseBranch, String branch, String token) {
    }

    /** 一个会话的工作区上下文：随会话存活，结束（push+清理）后弃置。 */
    public record RepoCtx(RepoSpec spec, Path cacheDir, Path sessionDir) {
    }

    private final Path workspaceRoot;

    public RunnerWorkspace(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /** 准备会话工作区：clone（首次）→ fetch 基线 → 会话 worktree；返回 workdir 所在上下文。 */
    public RepoCtx prepare(String sessionId, String projectId, RepoSpec spec) {
        if (projectId == null || !SAFE_ID.matcher(projectId).matches()) {
            throw new IllegalStateException("非法 projectId（白名单 [a-zA-Z0-9._-]）: " + projectId);
        }
        if (spec.branch() == null || !spec.branch().startsWith("feature/")) {
            throw new IllegalStateException("非法会话分支（必须 feature/ 前缀）: " + spec.branch());
        }
        Path cacheDir = workspaceRoot.resolve(projectId).resolve("main").normalize();
        Path sessionDir = workspaceRoot.resolve(projectId).resolve("sessions").resolve(sessionId).normalize();
        if (!cacheDir.startsWith(workspaceRoot) || !sessionDir.startsWith(workspaceRoot)) {
            throw new IllegalStateException("工作区路径越界（.. 逃逸防护）: " + projectId);
        }
        ensureClone(cacheDir, spec);
        fetch(cacheDir, spec);
        addWorktree(cacheDir, sessionDir, spec);
        return new RepoCtx(spec, cacheDir, sessionDir);
    }

    /**
     * 会话结束收口（best-effort）：push 会话分支（无新提交 = no-op 成功）→ 移除会话 worktree
     * （分支保留在克隆缓存供追溯）。push 失败只上报，不反转会话结局。
     */
    public void finish(RepoCtx ctx, java.util.function.Consumer<String> sink) {
        RepoSpec spec = ctx.spec();
        try {
            Result push = run(ctx.cacheDir(), PUSH_TIMEOUT_SEC, spec.token(),
                    "push", withToken(spec.remoteUrl(), spec.token()), spec.branch() + ":" + spec.branch());
            if (push.exit() == 0) {
                sink.accept(push.output().contains("Everything up-to-date")
                        ? "[工作区] 分支 " + spec.branch() + " 无新提交，远端已是最新"
                        : "[工作区] 已推送分支 " + spec.branch() + " 到远端");
            } else {
                sink.accept("[工作区] 分支 " + spec.branch() + " 推送失败（改动保留在节点 "
                        + ctx.cacheDir() + "，可人工 push）: " + tail(push.output()));
                log.warn("会话分支推送失败: branch={} err={}", spec.branch(), tail(push.output()));
            }
        } catch (Exception e) {
            sink.accept("[工作区] 分支推送异常（不反转会话结局）: " + e.getMessage());
            log.warn("会话分支推送异常: {}", e.getMessage());
        }
        try {
            Result rm = run(ctx.cacheDir(), OP_TIMEOUT_SEC, spec.token(),
                    "worktree", "remove", "--force", ctx.sessionDir().toString());
            if (rm.exit() != 0) {
                log.warn("会话 worktree 清理失败(可人工删除 {}): {}", ctx.sessionDir(), tail(rm.output()));
            }
        } catch (Exception e) {
            log.warn("会话 worktree 清理异常: {}", e.getMessage());
        }
    }

    /** 克隆缓存就位：已有 .git 直接复用；否则 clone（token 内嵌）+ 立即清 origin URL 残留 */
    private void ensureClone(Path cacheDir, RepoSpec spec) {
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

    /** 会话 worktree：目录在 = resume 复用；分支在 = 挂回既有分支；都没有 = 从基线新建 */
    private void addWorktree(Path cacheDir, Path sessionDir, RepoSpec spec) {
        if (Files.isDirectory(sessionDir)) {
            log.info("会话 worktree 已存在（resume 复用）: {}", sessionDir);
            return;
        }
        Result verify = run(cacheDir, OP_TIMEOUT_SEC, spec.token(),
                "rev-parse", "--verify", "--quiet", "refs/heads/" + spec.branch());
        Result add;
        if (verify.exit() == 0) {
            add = run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "add",
                    sessionDir.toString(), spec.branch());
        } else {
            String baseline = spec.baseBranch() != null && !spec.baseBranch().isBlank()
                    ? "FETCH_HEAD" : "HEAD";
            add = run(cacheDir, OP_TIMEOUT_SEC, spec.token(), "worktree", "add",
                    "-b", spec.branch(), sessionDir.toString(), baseline);
        }
        if (add.exit() != 0) {
            throw new IllegalStateException("git worktree add 失败: " + tail(add.output()));
        }
        log.info("会话 worktree 就绪: {} (branch {})", sessionDir, spec.branch());
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
        List<String> cmd = new ArrayList<>(List.of("git", "-C", cwd.toString()));
        cmd.addAll(List.of(args));
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

    static String sanitize(String s, String token) {
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
