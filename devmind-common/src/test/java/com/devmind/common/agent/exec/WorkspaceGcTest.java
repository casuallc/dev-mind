package com.devmind.common.agent.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-34 FR-05 {@link WorkspaceGc} 判定矩阵：老目录+已推送分支→删；老目录+未推送分支→留；
 * 新目录→留；活跃会话→留；chat 老沙箱→删；usageBytes 求和。
 * 真实 git 夹具沿用 {@link RunnerWorkspaceTest}（本地 bare 仓库当远端）。
 */
class WorkspaceGcTest {

    @TempDir
    Path tmp;

    private Path origin;
    private RunnerWorkspace ws;
    private Path wsRoot;

    private void seedOriginAndWorkspace() throws Exception {
        origin = tmp.resolve("origin.git");
        git(tmp, "init", "--bare", "-b", "main", origin.toString());
        Path seed = tmp.resolve("seed");
        git(tmp, "clone", origin.toString(), seed.toString());
        Files.writeString(seed.resolve("README.md"), "hello");
        git(seed, "add", ".");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        git(seed, "push", "origin", "main");
        wsRoot = tmp.resolve("workspaces");
        ws = new RunnerWorkspace(wsRoot);
    }

    /** prepare + 会话内提交一笔；push=true 时把分支推上远端（模拟 finish 的 push 但保留目录）。 */
    private Path orphanSessionDir(String sid, boolean push) throws Exception {
        // 存量旧布局手工搭建：共享缓存 <proj>/main + 会话 worktree <proj>/sessions/<sid>
        // （CAP-42 起 prepare 落 <proj>/<owner>/{main,work} 固定布局，GC 扫描的旧桶由本方法模拟）
        Path cacheDir = wsRoot.resolve("proj1").resolve("main");
        if (!Files.isDirectory(cacheDir.resolve(".git"))) {
            Files.createDirectories(cacheDir.getParent());
            git(cacheDir.getParent(), "clone", origin.toUri().toString(), cacheDir.toString());
        }
        git(cacheDir, "fetch", "origin", "main");
        Path sessionDir = wsRoot.resolve("proj1").resolve("sessions").resolve(sid);
        git(cacheDir, "worktree", "add", "-b", "feature/" + sid, sessionDir.toString(), "origin/main");
        Files.writeString(sessionDir.resolve("code.txt"), "change-" + sid);
        git(sessionDir, "add", ".");
        git(sessionDir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "work");
        if (push) {
            git(sessionDir, "push", "origin", "feature/" + sid + ":feature/" + sid);
        }
        return sessionDir;
    }

    private static void makeOld(Path dir) throws Exception {
        FileTime old = FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS));
        Files.setLastModifiedTime(dir, old);
    }

    @Test
    void gcDecisionMatrix() throws Exception {
        seedOriginAndWorkspace();
        Path pushed = orphanSessionDir("s-pushed", true);
        Path unpushed = orphanSessionDir("s-unpushed", false);
        Path active = orphanSessionDir("s-active", false);
        Path fresh = orphanSessionDir("s-fresh", true); // 已推送但不超龄
        for (Path d : List.of(pushed, unpushed, active)) {
            makeOld(d);
        }
        Path chatOld = wsRoot.resolve("_chat").resolve("c-old");
        Files.createDirectories(chatOld);
        Files.writeString(chatOld.resolve("draft.txt"), "x");
        makeOld(chatOld);

        var report = new WorkspaceGc(wsRoot).run(14, Set.of("s-active"));

        assertTrue(Files.notExists(pushed), "已推送+超龄应删除");
        assertTrue(Files.isDirectory(unpushed), "未推送分支应保留");
        assertTrue(Files.isDirectory(active), "活跃会话应保留");
        assertTrue(Files.isDirectory(fresh), "未超龄应保留");
        assertTrue(Files.notExists(chatOld), "chat 超龄沙箱应删除");
        assertEquals(2, report.deleted());
        assertTrue(report.skipped().stream().anyMatch(s -> s.startsWith("s-unpushed")),
                String.join("\n", report.skipped()));
        assertTrue(report.skipped().stream().anyMatch(s -> s.startsWith("s-active")),
                String.join("\n", report.skipped()));
    }

    @Test
    void usageBytesSumsWorkspace() throws Exception {
        seedOriginAndWorkspace();
        orphanSessionDir("s1", false);
        long usage = new WorkspaceGc(wsRoot).usageBytes();
        assertTrue(usage > 0);
        // 空根 = 0
        assertEquals(0, new WorkspaceGc(tmp.resolve("nonexistent")).usageBytes());
    }

    @Test
    void fixedPerUserWorkspaceIsNeverGcCandidate() throws Exception {
        // CAP-42：固定布局 <proj>/<owner>/{main,work} 不在 sessions/_chat 扫描桶下，
        // 超龄也绝不删除（收口只走手动 finalize）
        seedOriginAndWorkspace();
        RunnerWorkspace.RepoCtx ctx = ws.prepare("s1", "proj1", "alice", new RunnerWorkspace.RepoSpec(
                origin.toUri().toString(), "main", "feature/s1", ""));
        makeOld(ctx.sessionDir());
        makeOld(ctx.cacheDir());
        makeOld(ctx.sessionDir().getParent());

        var report = new WorkspaceGc(wsRoot).run(14, Set.of());
        assertEquals(0, report.deleted());
        assertTrue(Files.isDirectory(ctx.sessionDir()));
        assertTrue(Files.isDirectory(ctx.cacheDir()));
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
}
