package com.devmind.common.agent.exec;

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
    void chatSandboxLifecycle() throws Exception {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        // 幂等创建（resume 复用）：重复 prepare 不报错、内容保留
        Path dir = ws.prepareChat("chat01");
        assertEquals(tmp.resolve("workspaces").resolve("_chat").resolve("chat01").toAbsolutePath().normalize(),
                dir);
        Files.writeString(dir.resolve("note.txt"), "草稿");
        Path again = ws.prepareChat("chat01");
        assertEquals(dir, again);
        assertTrue(Files.exists(dir.resolve("note.txt")));

        // 结束收口：递归删除
        List<String> events = new ArrayList<>();
        ws.cleanChat("chat01", events::add);
        assertFalse(Files.exists(dir));
        assertTrue(events.stream().anyMatch(m -> m.contains("问答沙箱已清理")), String.join("\n", events));

        // 重复清理 no-op
        ws.cleanChat("chat01", events::add);
    }

    @Test
    void chatSandboxRejectsUnsafeId() {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        assertThrows(IllegalStateException.class, () -> ws.prepareChat("../escape"));
        assertThrows(IllegalStateException.class, () -> ws.prepareChat("a/b"));
    }

    @Test
    void multiRepoLifecycle() throws Exception {
        // 两个独立远端（file:// 匿名通道）
        Path originA = tmp.resolve("origin-a.git");
        Path originB = tmp.resolve("origin-b.git");
        seedOrigin(originA, "a.txt");
        seedOrigin(originB, "b.txt");

        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        List<RunnerWorkspace.RepoSpec> specs = List.of(
                new RunnerWorkspace.RepoSpec(originA.toUri().toString(), "main", "feature/s1", "", "backend"),
                new RunnerWorkspace.RepoSpec(originB.toUri().toString(), "main", "feature/s1", "", "web"));

        // prepareMulti：各库独立克隆缓存 <root>/<proj>/<name>/main + 会话 worktree sessions/<sid>/<name>，
        // 聚合根 = sessions/<sid>
        RunnerWorkspace.MultiCtx mctx = ws.prepareMulti("s1", "proj1", specs);
        Path aggRoot = tmp.resolve("workspaces").resolve("proj1").resolve("sessions").resolve("s1");
        assertEquals(aggRoot, mctx.aggRoot());
        assertEquals(aggRoot.resolve("backend"), mctx.repos().get(0).sessionDir());
        assertEquals(aggRoot.resolve("web"), mctx.repos().get(1).sessionDir());
        assertTrue(Files.exists(aggRoot.resolve("backend").resolve("a.txt")));
        assertTrue(Files.exists(aggRoot.resolve("web").resolve("b.txt")));
        assertEquals("feature/s1", gitOut(aggRoot.resolve("backend"), "branch", "--show-current"));
        assertEquals("feature/s1", gitOut(aggRoot.resolve("web"), "branch", "--show-current"));
        // 克隆缓存按库分目录
        assertTrue(Files.isDirectory(tmp.resolve("workspaces").resolve("proj1").resolve("backend")
                .resolve("main").resolve(".git")));
        assertTrue(Files.isDirectory(tmp.resolve("workspaces").resolve("proj1").resolve("web")
                .resolve("main").resolve(".git")));

        // 各库提交一笔 → finishMulti 逐库 push + 清理，聚合根删除
        Files.writeString(aggRoot.resolve("backend").resolve("code.txt"), "A");
        git(aggRoot.resolve("backend"), "add", ".");
        git(aggRoot.resolve("backend"), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "workA");
        Files.writeString(aggRoot.resolve("web").resolve("ui.txt"), "B");
        git(aggRoot.resolve("web"), "add", ".");
        git(aggRoot.resolve("web"), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "workB");

        List<String> events = new ArrayList<>();
        ws.finishMulti(mctx, events::add);
        assertTrue(events.stream().anyMatch(m -> m.contains("[backend]") && m.contains("已推送分支 feature/s1")),
                String.join("\n", events));
        assertTrue(events.stream().anyMatch(m -> m.contains("[web]") && m.contains("已推送分支 feature/s1")),
                String.join("\n", events));
        assertFalse(Files.exists(aggRoot));
        assertEquals("A", git(originA, "show", "feature/s1:code.txt").trim());
        assertEquals("B", git(originB, "show", "feature/s1:ui.txt").trim());
    }

    @Test
    void multiRepoRejectsUnsafeName() {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        List<RunnerWorkspace.RepoSpec> specs = List.of(
                new RunnerWorkspace.RepoSpec("file:///x", "main", "feature/s1", "", "ok"),
                new RunnerWorkspace.RepoSpec("file:///y", "main", "feature/s1", "", "../escape"));
        assertThrows(IllegalStateException.class, () -> ws.prepareMulti("s1", "proj1", specs));
    }

    private void seedOrigin(Path origin, String seedFile) throws Exception {
        git(tmp, "init", "--bare", "-b", "main", origin.toString());
        Path seed = tmp.resolve("seed-" + seedFile);
        git(tmp, "clone", origin.toString(), seed.toString());
        Files.writeString(seed.resolve(seedFile), "seed");
        git(seed, "add", ".");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        git(seed, "push", "origin", "main");
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
