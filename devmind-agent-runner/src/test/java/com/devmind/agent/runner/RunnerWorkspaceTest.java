package com.devmind.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-25 {@link RunnerWorkspace} 全流程集成测试：本地 bare 仓库当远端（file:// 匿名通道），
 * 覆盖 clone → fetch → 会话 worktree → 提交 → 结束 push+清理 → resume 复用分支。
 */
class RunnerWorkspaceTest {

    @TempDir
    Path tmp;

    @Test
    void fullLifecycle() throws Exception {
        Path origin = tmp.resolve("origin.git");
        git(tmp, "init", "--bare", "-b", "main", origin.toString());
        // 种一个初始提交到 main（经临时克隆推上去）
        Path seed = tmp.resolve("seed");
        git(tmp, "clone", origin.toString(), seed.toString());
        Files.writeString(seed.resolve("README.md"), "hello");
        git(seed, "add", ".");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        git(seed, "push", "origin", "main");

        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoSpec spec = new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s1", "");

        // prepare：clone 缓存 + 会话 worktree + feature/s1 分支
        RunnerWorkspace.RepoCtx ctx = ws.prepare("s1", "proj1", spec);
        assertTrue(Files.isDirectory(ctx.cacheDir().resolve(".git")));
        assertTrue(Files.isDirectory(ctx.sessionDir()));
        assertEquals("feature/s1", gitOut(ctx.sessionDir(), "branch", "--show-current"));
        // 初始提交在基线上
        assertTrue(Files.exists(ctx.sessionDir().resolve("README.md")));
        // 克隆缓存 origin URL 无凭据残留（本测试无 token，验证 set-url 路径不破坏 URL）
        assertEquals(origin.toUri().toString(),
                gitOut(ctx.cacheDir(), "remote", "get-url", "origin"));

        // 会话内提交一笔
        Files.writeString(ctx.sessionDir().resolve("code.txt"), "change");
        git(ctx.sessionDir(), "add", ".");
        git(ctx.sessionDir(), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "work");

        // finish：push 分支到远端 + 移除会话 worktree（分支保留在缓存）
        List<String> events = new ArrayList<>();
        ws.finish(ctx, events::add);
        assertTrue(events.stream().anyMatch(m -> m.contains("已推送分支 feature/s1")), String.join("\n", events));
        assertFalse(Files.exists(ctx.sessionDir()));
        assertEquals("change", git(origin, "show", "feature/s1:code.txt").trim());

        // resume：目录已清理但分支在 → 挂回既有分支，提交还在
        RunnerWorkspace.RepoCtx ctx2 = ws.prepare("s1", "proj1", spec);
        assertEquals("feature/s1", gitOut(ctx2.sessionDir(), "branch", "--show-current"));
        assertEquals("change", Files.readString(ctx2.sessionDir().resolve("code.txt")));
    }

    @Test
    void rejectsUnsafeIdsAndBranch() {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoSpec spec = new RunnerWorkspace.RepoSpec("file:///x", "main", "main", "");
        assertThrows(IllegalStateException.class, () -> ws.prepare("s1", "../escape", spec));
        // 分支必须 feature/ 前缀
        assertThrows(IllegalStateException.class, () -> ws.prepare("s1", "proj1", spec));
    }

    @Test
    void sanitizeMasksToken() {
        String out = RunnerWorkspace.sanitize("remote: oauth2:abc+123@host abc%2B123 done", "abc+123");
        assertFalse(out.contains("abc+123"));
        assertFalse(out.contains("abc%2B123"));
        assertTrue(out.contains("***"));
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
