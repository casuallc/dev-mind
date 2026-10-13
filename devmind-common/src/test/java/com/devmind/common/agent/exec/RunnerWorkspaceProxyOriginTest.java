package com.devmind.common.agent.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-58 FR-06 {@link RunnerWorkspace#ensureProxyOrigin} 集成面（真实 git + file:// 本地远端）：
 * 带 token 的 http(s) 仓库 → worktree origin 重写为代理 URL、缓存库 origin 不动、
 * broker 登记/幂等刷新；匿名通道与 ssh 远端不重写；release 删 worktree 时吊销凭据。
 */
class RunnerWorkspaceProxyOriginTest {

    @TempDir
    Path tmp;

    private static final String HTTP_REMOTE = "https://gitlab.example.com/group/repo.git";

    /** file:// 本地 bare 远端 + 一次 prepare（无 token，origin 保持裸 file URL）。 */
    private RunnerWorkspace.RepoCtx preparedWorktree(RunnerWorkspace ws) throws Exception {
        return preparedWorktree(ws, "a");
    }

    private RunnerWorkspace.RepoCtx preparedWorktree(RunnerWorkspace ws, String tag) throws Exception {
        Path base = tmp.resolve(tag);
        Files.createDirectories(base);
        Path origin = base.resolve("origin.git");
        git(base, "init", "--bare", "-b", "main", origin.toString());
        Path seed = base.resolve("seed");
        git(base, "clone", origin.toString(), seed.toString());
        Files.writeString(seed.resolve("README.md"), "hello");
        git(seed, "add", ".");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        git(seed, "push", "origin", "main");
        return ws.prepare("s1", "proj1", "alice",
                new RunnerWorkspace.RepoSpec(origin.toUri().toString(), "main", "feature/s1", ""));
    }

    @Test
    void rewritesWorktreeOriginAndRegistersCred() throws Exception {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoCtx ctx = preparedWorktree(ws);
        GitProxyBroker broker = new GitProxyBroker("http://127.0.0.1:18377/git");
        ws.setGitProxyBroker(broker);
        String cacheOrigin = gitOut(ctx.cacheDir(), "config", "--get", "remote.origin.url");

        RunnerWorkspace.RepoSpec httpSpec =
                new RunnerWorkspace.RepoSpec(HTTP_REMOTE, "main", "feature/s1", "pat-123");
        ws.ensureProxyOrigin(ctx.cacheDir(), ctx.sessionDir(), httpSpec);

        String rewritten = gitOut(ctx.sessionDir(), "config", "--worktree", "--get", "remote.origin.url");
        assertTrue(rewritten.startsWith("http://127.0.0.1:18377/git/"), rewritten);
        assertTrue(rewritten.endsWith("/group/repo.git"), rewritten);
        assertFalse(rewritten.contains("pat-123"), "代理 URL 不得携带 token");
        String key = broker.extractKey(rewritten);
        var entry = broker.lookup(key);
        assertTrue(entry.isPresent(), "broker 应登记该 key");
        assertEquals("https://gitlab.example.com", entry.get().upstreamOrigin());
        assertEquals("pat-123", entry.get().token());
        // 缓存库（共享 config）origin 不动：平台自身操作走 withToken 显式 URL，不依赖 origin
        assertEquals(cacheOrigin, gitOut(ctx.cacheDir(), "config", "--get", "remote.origin.url"));
        // worktree 级隔离开启
        assertEquals("true", gitOut(ctx.cacheDir(), "config", "--get", "extensions.worktreeConfig"));

        // 幂等：同 worktree 再次 launch（token 轮换）→ key 不变、URL 不重写、登记刷新
        ws.ensureProxyOrigin(ctx.cacheDir(), ctx.sessionDir(),
                new RunnerWorkspace.RepoSpec(HTTP_REMOTE, "main", "feature/s1", "pat-456"));
        assertEquals(rewritten, gitOut(ctx.sessionDir(), "config", "--worktree", "--get", "remote.origin.url"));
        assertEquals("pat-456", broker.lookup(key).orElseThrow().token());
        assertEquals(1, broker.size());
    }

    @Test
    void anonymousAndSshRemotesNotRewritten() throws Exception {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoCtx ctx = preparedWorktree(ws);
        GitProxyBroker broker = new GitProxyBroker("http://127.0.0.1:18377/git");
        ws.setGitProxyBroker(broker);

        // 匿名通道（token 空）不重写
        ws.ensureProxyOrigin(ctx.cacheDir(), ctx.sessionDir(),
                new RunnerWorkspace.RepoSpec(HTTP_REMOTE, "main", "feature/s1", ""));
        // ssh 远端不支持，不重写
        ws.ensureProxyOrigin(ctx.cacheDir(), ctx.sessionDir(),
                new RunnerWorkspace.RepoSpec("git@gitlab.example.com:group/repo.git", "main",
                        "feature/s1", "pat-1"));
        // broker 未装配（代理未启用）不重写
        RunnerWorkspace ws2 = new RunnerWorkspace(tmp.resolve("workspaces2"));
        RunnerWorkspace.RepoCtx ctx2 = preparedWorktree(ws2, "b");
        ws2.ensureProxyOrigin(ctx2.cacheDir(), ctx2.sessionDir(),
                new RunnerWorkspace.RepoSpec(HTTP_REMOTE, "main", "feature/s1", "pat-1"));

        assertEquals(0, broker.size());
        assertFalse(gitOut(ctx.sessionDir(), "config", "--get", "remote.origin.url")
                .contains("127.0.0.1:18377"));
        assertFalse(gitOut(ctx2.sessionDir(), "config", "--get", "remote.origin.url")
                .contains("127.0.0.1:18377"));
    }

    @Test
    void distinctWorktreesGetDistinctKeys() throws Exception {
        // 同缓存克隆的两个需求工作树（keyed 布局）各自持独立 key，互不串凭据
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoCtx ctx = preparedWorktree(ws);
        GitProxyBroker broker = new GitProxyBroker("http://127.0.0.1:18377/git");
        ws.setGitProxyBroker(broker);
        RunnerWorkspace.RepoSpec httpSpec =
                new RunnerWorkspace.RepoSpec(HTTP_REMOTE, "main", "feature/s1", "pat-a");
        ws.ensureProxyOrigin(ctx.cacheDir(), ctx.sessionDir(), httpSpec);
        // 第二个需求工作树（worktrees/k2）
        RunnerWorkspace.RepoCtx ctx2 = ws.prepare("s2", "proj1", "alice", "k2",
                new RunnerWorkspace.RepoSpec(
                        gitOut(ctx.cacheDir(), "config", "--get", "remote.origin.url"),
                        "main", "feature/s2", ""));
        ws.ensureProxyOrigin(ctx2.cacheDir(), ctx2.sessionDir(),
                new RunnerWorkspace.RepoSpec(HTTP_REMOTE, "main", "feature/s2", "pat-b"));

        String url1 = gitOut(ctx.sessionDir(), "config", "--worktree", "--get", "remote.origin.url");
        String url2 = gitOut(ctx2.sessionDir(), "config", "--worktree", "--get", "remote.origin.url");
        assertNotEquals(broker.extractKey(url1), broker.extractKey(url2));
        assertEquals("pat-a", broker.lookup(broker.extractKey(url1)).orElseThrow().token());
        assertEquals("pat-b", broker.lookup(broker.extractKey(url2)).orElseThrow().token());
    }

    @Test
    void releaseRevokesCred() throws Exception {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoCtx ctx = preparedWorktree(ws);
        GitProxyBroker broker = new GitProxyBroker("http://127.0.0.1:18377/git");
        ws.setGitProxyBroker(broker);
        RunnerWorkspace.RepoSpec httpSpec =
                new RunnerWorkspace.RepoSpec(HTTP_REMOTE, "main", "feature/s1", "pat-123");
        ws.ensureProxyOrigin(ctx.cacheDir(), ctx.sessionDir(), httpSpec);
        assertEquals(1, broker.size());

        RunnerWorkspace.ReleaseOutcome out = ws.release("proj1", "alice", List.of(httpSpec));
        assertEquals(0, out.exit(), out.output());
        assertEquals(0, broker.size(), "worktree 删除后代理凭据必须吊销（再访问代理 404）");
        assertFalse(Files.isDirectory(ctx.sessionDir()));
    }

    private static String git(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", cwd.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + out);
        }
        return out;
    }

    private static String gitOut(Path cwd, String... args) throws Exception {
        return git(cwd, args).trim();
    }
}
