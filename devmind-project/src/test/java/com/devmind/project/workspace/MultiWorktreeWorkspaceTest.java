package com.devmind.project.workspace;

import com.devmind.project.WorktreeManager;
import com.devmind.project.config.WorktreeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-31 聚合目录多库工作区测试：两个本地 git 库 → 聚合根 + 各库 worktree 子目录 →
 * 清理倒序移除并删聚合根。fetch 无远端会失败回退本地 base（WorktreeManager best-effort 语义）。
 */
class MultiWorktreeWorkspaceTest {

    @TempDir
    Path tmp;

    @Test
    void multiRepoAggregateLifecycle() throws Exception {
        Path repoA = seedRepo("repo-a");
        Path repoB = seedRepo("repo-b");
        WorkspaceService svc = new WorkspaceService(new WorktreeManager(new WorktreeProperties(), null));

        List<WorkspaceService.SessionRepoSpec> specs = List.of(
                new WorkspaceService.SessionRepoSpec("backend", repoA.toString(), "main"),
                new WorkspaceService.SessionRepoSpec("web app", repoB.toString(), "main"));
        Workspace ws = svc.prepareSessionWorkspace(specs, "s1");

        // 聚合根 = 首库（主库）.devmind/worktrees/s1；子目录名 sanitize（空格→-）
        Path aggRoot = repoA.resolve(".devmind").resolve("worktrees").resolve("s1");
        assertEquals(MultiWorktreeWorkspace.TYPE, ws.type());
        assertEquals(aggRoot, ws.path());
        Path dirA = aggRoot.resolve("backend");
        Path dirB = aggRoot.resolve("web-app"); // "web app" → 空格 sanitize 为 -
        assertTrue(Files.exists(dirA.resolve(".git"))); // worktree 的 .git 是指针文件
        assertTrue(Files.exists(dirB.resolve(".git")));
        assertEquals("feature/s1", gitOut(dirA, "branch", "--show-current"));
        assertEquals("feature/s1", gitOut(dirB, "branch", "--show-current"));
        assertTrue(Files.exists(dirA.resolve("repo-a.txt")));
        assertTrue(Files.exists(dirB.resolve("repo-b.txt")));

        // 清理：两个 worktree 移除 + 聚合根删除
        svc.cleanupSessionWorkspace(specs, "s1", aggRoot);
        assertFalse(Files.exists(dirA));
        assertFalse(Files.exists(dirB));
        assertFalse(Files.exists(aggRoot));
    }

    @Test
    void singleRepoKeepsLegacyLayout() throws Exception {
        Path repoA = seedRepo("repo-a");
        WorkspaceService svc = new WorkspaceService(new WorktreeManager(new WorktreeProperties(), null));

        Workspace ws = svc.prepareSessionWorkspace(
                List.of(new WorkspaceService.SessionRepoSpec("backend", repoA.toString(), "main")), "s2");

        // 单库保持现状：cwd = 该库 worktree 本身（非聚合根）
        Path dir = repoA.resolve(".devmind").resolve("worktrees").resolve("s2");
        assertEquals(LocalWorktreeWorkspace.TYPE, ws.type());
        assertEquals(dir, ws.path());
        assertEquals("feature/s2", gitOut(dir, "branch", "--show-current"));

        svc.cleanupSessionWorkspace(
                List.of(new WorkspaceService.SessionRepoSpec("backend", repoA.toString(), "main")), "s2", dir);
        assertFalse(Files.exists(dir));
    }

    @Test
    void duplicateNamesGetSuffixed() throws Exception {
        Path repoA = seedRepo("repo-a");
        Path repoB = seedRepo("repo-b");
        WorkspaceService svc = new WorkspaceService(new WorktreeManager(new WorktreeProperties(), null));

        // 同名两库：第二个追加 -2
        List<WorkspaceService.SessionRepoSpec> specs = List.of(
                new WorkspaceService.SessionRepoSpec("app", repoA.toString(), "main"),
                new WorkspaceService.SessionRepoSpec("app", repoB.toString(), "main"));
        Workspace ws = svc.prepareSessionWorkspace(specs, "s3");
        Path aggRoot = repoA.resolve(".devmind").resolve("worktrees").resolve("s3");
        assertTrue(Files.exists(aggRoot.resolve("app").resolve(".git")));
        assertTrue(Files.exists(aggRoot.resolve("app-2").resolve(".git")));
        ws.cleanup();
        assertFalse(Files.exists(aggRoot));
    }

    private Path seedRepo(String name) throws Exception {
        Path repo = tmp.resolve(name);
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve(name + ".txt"), "seed");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        return repo;
    }

    private static String git(Path cwd, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", cwd.toString()));
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
