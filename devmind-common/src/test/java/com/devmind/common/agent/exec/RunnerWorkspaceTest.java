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
 * CAP-25 {@link RunnerWorkspace} 全流程集成测试：本地 bare 仓库当远端（file:// 匿名通道）。
 * CAP-42 起覆盖每用户固定布局：clone 缓存 &lt;proj&gt;/&lt;owner&gt;/main + 固定 worktree
 * &lt;proj&gt;/&lt;owner&gt;/work → 会话提交 → finish 不 push 不删（脏检查告警）→ resume 复用。
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

        // CAP-42 prepare：克隆缓存 <root>/<proj>/<owner>/main + 固定 worktree <proj>/<owner>/work
        RunnerWorkspace.RepoCtx ctx = ws.prepare("s1", "proj1", "alice", spec);
        assertEquals(tmp.resolve("workspaces").resolve("proj1").resolve("alice").resolve("main")
                .toAbsolutePath().normalize(), ctx.cacheDir());
        assertEquals(tmp.resolve("workspaces").resolve("proj1").resolve("alice").resolve("work")
                .toAbsolutePath().normalize(), ctx.sessionDir());
        assertTrue(Files.isDirectory(ctx.cacheDir().resolve(".git")));
        assertTrue(Files.isDirectory(ctx.sessionDir()));
        assertEquals("feature/s1", gitOut(ctx.sessionDir(), "branch", "--show-current"));
        // 初始提交在基线上
        assertTrue(Files.exists(ctx.sessionDir().resolve("README.md")));
        // 克隆缓存 origin URL 无凭据残留（本测试无 token，验证 set-url 路径不破坏 URL）
        assertEquals(origin.toUri().toString(),
                gitOut(ctx.cacheDir(), "remote", "get-url", "origin"));
        // .runner-pid 被 info/exclude 排除：agent「git add -A」不会把 pid 文件提交进会话分支
        // （否则会话结束删 pid 后工作区恒脏，收口被「未提交改动」挡住——E2E 实测踩中）
        assertTrue(Files.readString(ctx.cacheDir().resolve(".git/info/exclude")).contains("/.runner-pid"));
        Files.writeString(ctx.sessionDir().resolve(".runner-pid"), "1 2026-09-15");
        git(ctx.sessionDir(), "add", "-A");
        assertEquals("", gitOut(ctx.sessionDir(), "status", "--porcelain"));
        Files.delete(ctx.sessionDir().resolve(".runner-pid"));

        // 会话内提交一笔
        Files.writeString(ctx.sessionDir().resolve("code.txt"), "change");
        git(ctx.sessionDir(), "add", ".");
        git(ctx.sessionDir(), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "work");

        // CAP-42 finish：不 push、不删 worktree；工作区干净 → 无告警
        List<String> events = new ArrayList<>();
        ws.finish(ctx, events::add);
        assertTrue(events.isEmpty(), String.join("\n", events));
        assertTrue(Files.isDirectory(ctx.sessionDir()), "固定 worktree 结束不删除");
        // 远端无自动 push（收口合并前分支不上远端）
        assertThrows(IllegalStateException.class,
                () -> git(origin, "rev-parse", "--verify", "refs/heads/feature/s1"));

        // resume：worktree 在且分支一致 → 直接复用，提交还在
        RunnerWorkspace.RepoCtx ctx2 = ws.prepare("s1", "proj1", "alice", spec);
        assertEquals(ctx.sessionDir(), ctx2.sessionDir());
        assertEquals("feature/s1", gitOut(ctx2.sessionDir(), "branch", "--show-current"));
        assertEquals("change", Files.readString(ctx2.sessionDir().resolve("code.txt")));
    }

    @Test
    void occupancyConflictBlocksSecondSession() throws Exception {
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, "README.md");
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoSpec spec1 = new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s1", "");
        ws.prepare("s1", "proj1", "alice", spec1);

        // 同用户同项目第二个会话（不同分支）→ 占用冲突，错误带占用会话 sid 引导收口
        RunnerWorkspace.RepoSpec spec2 = new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s2", "");
        var e = assertThrows(IllegalStateException.class, () -> ws.prepare("s2", "proj1", "alice", spec2));
        assertTrue(e.getMessage().contains("s1"), e.getMessage());
        assertTrue(e.getMessage().contains("收口"), e.getMessage());

        // 不同用户互不影响（独立缓存与 worktree）
        RunnerWorkspace.RepoCtx bob = ws.prepare("s2", "proj1", "bob", spec2);
        assertTrue(Files.isDirectory(bob.sessionDir()));
        assertEquals("feature/s2", gitOut(bob.sessionDir(), "branch", "--show-current"));
    }

    @Test
    void finishWarnsOnUncommittedChanges() throws Exception {
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, "README.md");
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoCtx ctx = ws.prepare("s1", "proj1", "alice", new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s1", ""));

        Files.writeString(ctx.sessionDir().resolve("dirty.txt"), "x");
        List<String> events = new ArrayList<>();
        ws.finish(ctx, events::add);
        assertTrue(events.stream().anyMatch(m -> m.contains("未提交改动")), String.join("\n", events));
        assertTrue(Files.isDirectory(ctx.sessionDir()), "脏工作区同样不删（改动保留待人工处理）");
    }

    @Test
    void resumeAttachesToPushedBranchInFreshCache() throws Exception {
        // 老会话已 push 分支（旧布局结束 push 的存量成果）；全新 per-user 缓存 resume →
        // 本地无分支但 origin/ 有 → 从远端分支挂回，不从基线新建分叉
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, "README.md");
        Path legacy = tmp.resolve("legacy");
        git(tmp, "clone", origin.toString(), legacy.toString());
        git(legacy, "checkout", "-b", "feature/s1");
        Files.writeString(legacy.resolve("code.txt"), "change");
        git(legacy, "add", ".");
        git(legacy, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "work");
        git(legacy, "push", "origin", "feature/s1");

        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoCtx ctx = ws.prepare("s1", "proj1", "alice", new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s1", ""));
        assertEquals("feature/s1", gitOut(ctx.sessionDir(), "branch", "--show-current"));
        assertEquals("change", Files.readString(ctx.sessionDir().resolve("code.txt")));
        // 本地分支基于 origin/feature/s1（含会话提交），不是基线新建
        assertEquals(gitOut(origin, "rev-parse", "feature/s1"),
                gitOut(ctx.sessionDir(), "rev-parse", "HEAD"));
    }

    @Test
    void rejectsUnsafeIdsAndBranch() {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoSpec badBranch = new RunnerWorkspace.RepoSpec("file:///x", "main", "main", "");
        RunnerWorkspace.RepoSpec spec = new RunnerWorkspace.RepoSpec("file:///x", "main", "feature/s1", "");
        assertThrows(IllegalStateException.class, () -> ws.prepare("s1", "../escape", "alice", badBranch));
        // 分支必须 feature/ 前缀
        assertThrows(IllegalStateException.class, () -> ws.prepare("s1", "proj1", "alice", badBranch));
        // CAP-42 FR-07：owner 白名单 + 保留名
        assertThrows(IllegalStateException.class, () -> ws.prepare("s1", "proj1", "中文 名", spec));
        assertThrows(IllegalStateException.class, () -> ws.prepare("s1", "proj1", "work", spec));
        assertThrows(IllegalStateException.class, () -> ws.prepare("s1", "proj1", "sessions", spec));
        assertThrows(IllegalStateException.class, () -> ws.prepare("s1", "proj1", null, spec));
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

        // CAP-42 prepareMulti：各库独立克隆缓存 <root>/<proj>/<owner>/<name>/main +
        // 固定子 worktree <proj>/<owner>/work/<name>，聚合根 = <proj>/<owner>/work
        RunnerWorkspace.MultiCtx mctx = ws.prepareMulti("s1", "proj1", "alice", specs);
        Path aggRoot = tmp.resolve("workspaces").resolve("proj1").resolve("alice").resolve("work");
        assertEquals(aggRoot, mctx.aggRoot());
        assertEquals(aggRoot.resolve("backend"), mctx.repos().get(0).sessionDir());
        assertEquals(aggRoot.resolve("web"), mctx.repos().get(1).sessionDir());
        assertTrue(Files.exists(aggRoot.resolve("backend").resolve("a.txt")));
        assertTrue(Files.exists(aggRoot.resolve("web").resolve("b.txt")));
        assertEquals("feature/s1", gitOut(aggRoot.resolve("backend"), "branch", "--show-current"));
        assertEquals("feature/s1", gitOut(aggRoot.resolve("web"), "branch", "--show-current"));
        // 克隆缓存按用户按库分目录
        assertTrue(Files.isDirectory(tmp.resolve("workspaces").resolve("proj1").resolve("alice")
                .resolve("backend").resolve("main").resolve(".git")));
        assertTrue(Files.isDirectory(tmp.resolve("workspaces").resolve("proj1").resolve("alice")
                .resolve("web").resolve("main").resolve(".git")));

        // 各库提交一笔 → CAP-42 finishMulti 不 push 不删，干净工作区无告警
        Files.writeString(aggRoot.resolve("backend").resolve("code.txt"), "A");
        git(aggRoot.resolve("backend"), "add", ".");
        git(aggRoot.resolve("backend"), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "workA");
        Files.writeString(aggRoot.resolve("web").resolve("ui.txt"), "B");
        git(aggRoot.resolve("web"), "add", ".");
        git(aggRoot.resolve("web"), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "workB");

        List<String> events = new ArrayList<>();
        ws.finishMulti(mctx, events::add);
        assertTrue(events.isEmpty(), String.join("\n", events));
        assertTrue(Files.isDirectory(aggRoot), "多库聚合根结束不删除");
        assertThrows(IllegalStateException.class,
                () -> git(originA, "rev-parse", "--verify", "refs/heads/feature/s1"));

        // 脏检查：web 库留未提交改动 → finishMulti 带 [web] 前缀告警
        Files.writeString(aggRoot.resolve("web").resolve("dirty.txt"), "x");
        ws.finishMulti(mctx, events::add);
        assertTrue(events.stream().anyMatch(m -> m.contains("[web]") && m.contains("未提交改动")),
                String.join("\n", events));
    }

    @Test
    void multiRepoRejectsUnsafeName() {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        List<RunnerWorkspace.RepoSpec> specs = List.of(
                new RunnerWorkspace.RepoSpec("file:///x", "main", "feature/s1", "", "ok"),
                new RunnerWorkspace.RepoSpec("file:///y", "main", "feature/s1", "", "../escape"));
        assertThrows(IllegalStateException.class, () -> ws.prepareMulti("s1", "proj1", "alice", specs));
        // 保留名 work（与聚合根撞名）
        List<RunnerWorkspace.RepoSpec> reserved = List.of(
                new RunnerWorkspace.RepoSpec("file:///x", "main", "feature/s1", "", "work"),
                new RunnerWorkspace.RepoSpec("file:///y", "main", "feature/s1", "", "ok"));
        assertThrows(IllegalStateException.class, () -> ws.prepareMulti("s1", "proj1", "alice", reserved));
    }

    @Test
    void finalizeMergesPushesAndRemovesWorktree() throws Exception {
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, "README.md");
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoSpec spec = new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s1", "");
        RunnerWorkspace.RepoCtx ctx = ws.prepare("s1", "proj1", "alice", spec);
        Files.writeString(ctx.sessionDir().resolve("code.txt"), "change");
        git(ctx.sessionDir(), "add", ".");
        git(ctx.sessionDir(), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "work");

        RunnerWorkspace.FinalizeOutcome r = ws.finalize("proj1", "alice", List.of(spec), false);
        assertEquals(0, r.exit(), r.output());
        // 基线含合并提交（merge --no-ff + 收口消息）与会话产出
        assertEquals("change", git(origin, "show", "main:code.txt").trim());
        assertTrue(git(origin, "log", "--oneline", "main").contains("收口"), "基线应有收口合并提交");
        // 会话分支 best-effort 推送远端（供收口后 diff）
        assertFalse(gitOut(origin, "rev-parse", "--verify", "refs/heads/feature/s1").isBlank());
        // 固定 worktree 与本地分支已删；临时合并目录已清
        assertFalse(Files.exists(ctx.sessionDir()), "收口后固定 worktree 删除");
        assertEquals("", gitOut(ctx.cacheDir(), "branch", "--list", "feature/s1"));
        assertFalse(Files.exists(tmp.resolve("workspaces").resolve("proj1").resolve("alice")
                .resolve(".finalize-tmp")));
        // 缓存仍在（复用），origin URL 无凭据残留
        assertTrue(Files.isDirectory(ctx.cacheDir().resolve(".git")));
        // 收口后同用户可开新会话（占用释放）
        RunnerWorkspace.RepoCtx s2 = ws.prepare("s2", "proj1", "alice", new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s2", ""));
        assertEquals("feature/s2", gitOut(s2.sessionDir(), "branch", "--show-current"));
        // 新会话基线含已收口的产出
        assertEquals("change", Files.readString(s2.sessionDir().resolve("code.txt")));
    }

    @Test
    void finalizeFailsOnMergeConflictAndKeepsWorktree() throws Exception {
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, "README.md");
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoSpec spec = new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s1", "");
        RunnerWorkspace.RepoCtx ctx = ws.prepare("s1", "proj1", "alice", spec);
        Files.writeString(ctx.sessionDir().resolve("README.md"), "session-change");
        git(ctx.sessionDir(), "add", ".");
        git(ctx.sessionDir(), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "work");
        // 基线被他人推进（同一文件冲突改动）
        Path other = tmp.resolve("other");
        git(tmp, "clone", origin.toString(), other.toString());
        Files.writeString(other.resolve("README.md"), "other-change");
        git(other, "add", ".");
        git(other, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "other");
        git(other, "push", "origin", "main");

        RunnerWorkspace.FinalizeOutcome r = ws.finalize("proj1", "alice", List.of(spec), false);
        assertTrue(r.exit() != 0, r.output());
        assertTrue(r.output().contains("冲突"), r.output());
        // 工作区保留（可 resume 解冲突后重试），基线未被污染，临时目录已清
        assertTrue(Files.isDirectory(ctx.sessionDir()));
        assertEquals("feature/s1", gitOut(ctx.sessionDir(), "branch", "--show-current"));
        assertEquals("session-change", Files.readString(ctx.sessionDir().resolve("README.md")));
        assertEquals("other-change", git(origin, "show", "main:README.md").trim());
        assertFalse(Files.exists(tmp.resolve("workspaces").resolve("proj1").resolve("alice")
                .resolve(".finalize-tmp")));
    }

    @Test
    void finalizeFailsOnDirtyWorktreeUnlessDiscarded() throws Exception {
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, "README.md");
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoSpec spec = new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s1", "");
        RunnerWorkspace.RepoCtx ctx = ws.prepare("s1", "proj1", "alice", spec);
        Files.writeString(ctx.sessionDir().resolve("code.txt"), "change");
        git(ctx.sessionDir(), "add", ".");
        git(ctx.sessionDir(), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "work");
        Files.writeString(ctx.sessionDir().resolve("dirty.txt"), "uncommitted");

        // 未提交改动 + 不丢弃 → 失败保留现场
        RunnerWorkspace.FinalizeOutcome dirty = ws.finalize("proj1", "alice", List.of(spec), false);
        assertTrue(dirty.exit() != 0, dirty.output());
        assertTrue(dirty.output().contains("未提交改动"), dirty.output());
        assertTrue(Files.exists(ctx.sessionDir().resolve("dirty.txt")));

        // discardChanges=true → reset+clean 后正常收口
        RunnerWorkspace.FinalizeOutcome r = ws.finalize("proj1", "alice", List.of(spec), true);
        assertEquals(0, r.exit(), r.output());
        assertEquals("change", git(origin, "show", "main:code.txt").trim());
        assertFalse(Files.exists(ctx.sessionDir()));
    }

    @Test
    void finalizeMultiRepoPartialFailureKeepsFailedRepo() throws Exception {
        Path originA = tmp.resolve("origin-a.git");
        Path originB = tmp.resolve("origin-b.git");
        seedOrigin(originA, "a.txt");
        seedOrigin(originB, "b.txt");
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        List<RunnerWorkspace.RepoSpec> specs = List.of(
                new RunnerWorkspace.RepoSpec(originA.toUri().toString(), "main", "feature/s1", "", "backend"),
                new RunnerWorkspace.RepoSpec(originB.toUri().toString(), "main", "feature/s1", "", "web"));
        RunnerWorkspace.MultiCtx mctx = ws.prepareMulti("s1", "proj1", "alice", specs);
        Path aggRoot = mctx.aggRoot();
        // 两库各提交一笔；web 库制造基线冲突
        Files.writeString(aggRoot.resolve("backend").resolve("code.txt"), "A");
        git(aggRoot.resolve("backend"), "add", ".");
        git(aggRoot.resolve("backend"), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "workA");
        Files.writeString(aggRoot.resolve("web").resolve("b.txt"), "session-change");
        git(aggRoot.resolve("web"), "add", ".");
        git(aggRoot.resolve("web"), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "workB");
        Path other = tmp.resolve("other-b");
        git(tmp, "clone", originB.toString(), other.toString());
        Files.writeString(other.resolve("b.txt"), "other-change");
        git(other, "add", ".");
        git(other, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "other");
        git(other, "push", "origin", "main");

        RunnerWorkspace.FinalizeOutcome r = ws.finalize("proj1", "alice", specs, false);
        assertTrue(r.exit() != 0, r.output());
        assertTrue(r.output().contains("[web]"), r.output());
        // 成功库已收口：基线含产出、子 worktree 已删；失败库保留
        assertEquals("A", git(originA, "show", "main:code.txt").trim());
        assertFalse(Files.exists(aggRoot.resolve("backend")));
        assertTrue(Files.isDirectory(aggRoot.resolve("web")));
        assertEquals("other-change", git(originB, "show", "main:b.txt").trim());
        // 失败库解冲突（改为与基线一致的内容再提交）后重试收口成功
        Files.writeString(aggRoot.resolve("web").resolve("b.txt"), "other-change");
        git(aggRoot.resolve("web"), "add", ".");
        git(aggRoot.resolve("web"), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "resolve");
        Files.writeString(aggRoot.resolve("web").resolve("ui.txt"), "B");
        git(aggRoot.resolve("web"), "add", ".");
        git(aggRoot.resolve("web"), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "ui");
        RunnerWorkspace.FinalizeOutcome retry = ws.finalize("proj1", "alice", specs, false);
        assertEquals(0, retry.exit(), retry.output());
        assertEquals("B", git(originB, "show", "main:ui.txt").trim());
        // 全部收口成功 → 聚合根已删
        assertFalse(Files.exists(aggRoot));
    }

    @Test
    void finalizeRejectsMissingOrMismatchedWorktree() throws Exception {
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, "README.md");
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        RunnerWorkspace.RepoSpec spec = new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s1", "");
        RunnerWorkspace.RepoCtx ctx = ws.prepare("s1", "proj1", "alice", spec);

        // 分支不符（工作区被别的会话占用）→ 拒绝
        RunnerWorkspace.FinalizeOutcome wrong = ws.finalize("proj1", "alice", List.of(
                new RunnerWorkspace.RepoSpec(origin.toUri().toString(), "main", "feature/s9", "")), false);
        assertTrue(wrong.exit() != 0, wrong.output());
        assertTrue(wrong.output().contains("不一致"), wrong.output());

        // worktree 不存在（已收口/未初始化）→ 拒绝
        deleteRec(ctx.sessionDir());
        RunnerWorkspace.FinalizeOutcome missing = ws.finalize("proj1", "alice", List.of(spec), false);
        assertTrue(missing.exit() != 0, missing.output());
        assertTrue(missing.output().contains("不存在"), missing.output());
    }

    private static void deleteRec(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void buildWorkspaceLifecycle() throws Exception {
        // CAP-36：构建工作区——clone 缓存复用 <proj>/main，builds/<id> detach 到 commit，
        // 幂等复用（链内后续步骤不再 fetch），finishBuild 移除
        Path origin = tmp.resolve("origin.git");
        seedOrigin(origin, "README.md");
        String head = gitOut(origin, "rev-parse", "main");

        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        Path dir = ws.prepareBuild("build-1", "proj1", origin.toUri().toString(), "main", head, null);
        assertEquals(tmp.resolve("workspaces").resolve("proj1").resolve("builds").resolve("build-1")
                .toAbsolutePath().normalize(), dir);
        assertTrue(Files.exists(dir.resolve("README.md")));
        // detach：无分支
        assertEquals("", gitOut(dir, "branch", "--show-current"));
        assertEquals(head, gitOut(dir, "rev-parse", "HEAD"));
        // 克隆缓存复用会话链路同一目录
        assertTrue(Files.isDirectory(tmp.resolve("workspaces").resolve("proj1").resolve("main").resolve(".git")));

        // 幂等：同 workspaceId 再 prepare 直接复用（前步骤产物保留）
        Files.writeString(dir.resolve("target.txt"), "built");
        Path again = ws.prepareBuild("build-1", "proj1", origin.toUri().toString(), "main", head, null);
        assertEquals(dir, again);
        assertTrue(Files.exists(again.resolve("target.txt")));

        // 收口：worktree 移除；重复收口 no-op
        ws.finishBuild("proj1", "build-1", null);
        assertFalse(Files.exists(dir));
        ws.finishBuild("proj1", "build-1", null);
    }

    @Test
    void buildWorkspaceRejectsUnsafeIds() {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        assertThrows(IllegalStateException.class,
                () -> ws.prepareBuild("../escape", "proj1", "file:///x", "main", "", null));
        assertThrows(IllegalStateException.class,
                () -> ws.prepareBuild("build-1", "../escape", "file:///x", "main", "", null));
        assertThrows(IllegalStateException.class,
                () -> ws.prepareBuild("build-1", "proj1", " ", "main", "", null));
    }

    private void seedOrigin(Path origin, String seedFile) throws Exception {        git(tmp, "init", "--bare", "-b", "main", origin.toString());
        Path seed = tmp.resolve("seed-" + seedFile);
        git(tmp, "clone", origin.toString(), seed.toString());
        Files.writeString(seed.resolve(seedFile), "seed");
        git(seed, "add", ".");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        git(seed, "push", "origin", "main");
    }

    @Test
    void worklogPushLifecycle() throws Exception {
        // CAP-41 M3：file:// bare 库当远端（匿名通道），覆盖 绑定 origin → 首次 push →
        // 增量 push → up-to-date → ssh 拒绝
        Path origin = tmp.resolve("worklog-origin.git");
        git(tmp, "init", "--bare", "-b", "main", origin.toString());

        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        Path dir = ws.prepareWorklog(tmp.resolve("worklog"), "alice");
        assertTrue(Files.isDirectory(dir.resolve(".git")));

        // 首次 push：origin 幂等绑定 + main 分支建立，骨架提交上远端
        RunnerWorkspace.WorklogPushOutcome first =
                ws.pushWorklog(dir, origin.toUri().toString(), "main", null);
        assertEquals(0, first.exit(), first.output());
        assertTrue(git(origin, "show", "main:README.md").contains("工作日志空间"));
        // origin URL 为 cleanUrl（本测试无 token，验证绑定逻辑本身）
        assertEquals(origin.toUri().toString(), gitOut(dir, "remote", "get-url", "origin"));

        // 增量：新成稿 commit 后 push，远端可见
        Files.writeString(dir.resolve("daily").resolve("2026-09-14.md"), "# 日报");
        git(dir, "add", "-A");
        git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "docs: daily 2026-09-14");
        RunnerWorkspace.WorklogPushOutcome second =
                ws.pushWorklog(dir, origin.toUri().toString(), "main", null);
        assertEquals(0, second.exit(), second.output());
        assertEquals("# 日报", git(origin, "show", "main:daily/2026-09-14.md").trim());

        // up-to-date 也算成功
        RunnerWorkspace.WorklogPushOutcome third =
                ws.pushWorklog(dir, origin.toUri().toString(), "main", null);
        assertEquals(0, third.exit(), third.output());

        // 未初始化目录 / ssh URL 明确拒绝
        assertTrue(ws.pushWorklog(tmp.resolve("nope"), origin.toUri().toString(), "main", null).exit() != 0);
        RunnerWorkspace.WorklogPushOutcome ssh = ws.pushWorklog(dir, "git@example.com:a/b.git", "main", null);
        assertTrue(ssh.exit() != 0);
        assertTrue(ssh.output().contains("http"), ssh.output());
    }

    @Test
    void worklogPushNeverLeaksToken() throws Exception {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        Path dir = ws.prepareWorklog(tmp.resolve("worklog"), "bob");
        // 打不通的 https 远端 + token：失败输出不得含 token 明文/URL 编码形态
        RunnerWorkspace.WorklogPushOutcome r = ws.pushWorklog(
                dir, "https://127.0.0.1:1/x/y.git", "main", "tok+abc123");
        assertTrue(r.exit() != 0);
        assertFalse(r.output().contains("tok+abc123"), r.output());
        assertFalse(r.output().contains("tok%2Babc123"), r.output());
        // 失败也不许把 token 写进 .git/config
        assertEquals("https://127.0.0.1:1/x/y.git", gitOut(dir, "remote", "get-url", "origin"));
    }

    @Test
    void worklogSkeletonGitignoresPlatformFiles() throws Exception {
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        Path dir = ws.prepareWorklog(tmp.resolve("worklog"), "alice");
        // 骨架含 .gitignore 且随骨架 commit 入库（忽略规则随远端备份一起走）
        assertTrue(Files.exists(dir.resolve(".gitignore")));
        assertEquals("", gitOut(dir, "status", "--porcelain"));
        assertFalse(gitOut(dir, "ls-files", ".gitignore").isBlank());
        // 平台托管文件（上下文装配/物化设置/回传产物）被忽略：agent「git add -A」扫不进仓库
        Files.writeString(dir.resolve("CLAUDE.md"), "<!-- 装配产物 -->");
        Files.createDirectories(dir.resolve(".claude"));
        Files.writeString(dir.resolve(".claude").resolve("settings.local.json"), "{}");
        Files.createDirectories(dir.resolve(".devmind").resolve("output"));
        Files.writeString(dir.resolve(".devmind").resolve("output").resolve("daily-2026-09-16.md"), "# 日报");
        git(dir, "add", "-A");
        assertEquals("", gitOut(dir, "status", "--porcelain"));
    }

    @Test
    void worklogPrepareBackfillsGitignoreForLegacySpace() throws Exception {
        // 存量空间（老骨架无 .gitignore）幂等复用时补写
        Path dir = tmp.resolve("worklog").resolve("bob");
        Files.createDirectories(dir);
        git(dir, "init");
        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        ws.prepareWorklog(tmp.resolve("worklog"), "bob");
        assertTrue(Files.readString(dir.resolve(".gitignore")).contains(".devmind/"));
        // 用户自行加过规则 → 不覆盖
        Files.writeString(dir.resolve(".gitignore"), "custom-rule\n");
        ws.prepareWorklog(tmp.resolve("worklog"), "bob");
        assertEquals("custom-rule\n", Files.readString(dir.resolve(".gitignore")));
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
