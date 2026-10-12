package com.devmind.integration.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-74：GitRemoteOps 服务端仓写侧扩展——initRepo / addCommit（-F 中文 message、署名、
 * 日期回填）/ lastCommitByPath（批量单调用、trailer 解析）/ mergeNoEdit+mergeAbort / refspec push。
 * 全部真 git 子进程（@TempDir），防 Windows GBK 乱码回归。
 */
class GitRemoteOpsCommitLogTest {

    @TempDir
    Path tmp;

    private final GitRemoteOps ops = new GitRemoteOps(new org.springframework.beans.factory.ObjectProvider<com.devmind.common.egress.EgressProxyRouter>() {
        @Override
        public com.devmind.common.egress.EgressProxyRouter getObject() {
            return null;
        }

        @Override
        public com.devmind.common.egress.EgressProxyRouter getObject(Object... args) {
            return null;
        }

        @Override
        public com.devmind.common.egress.EgressProxyRouter getIfAvailable() {
            return null;
        }
    });

    private Path initRepo(String name) throws Exception {
        Path dir = tmp.resolve(name);
        GitRemoteOps.GitResult r = ops.initRepo(dir.toString(), "main");
        assertTrue(r.ok(), () -> "initRepo 失败: " + r.output());
        return dir;
    }

    private void commit(Path repo, String relPath, String content, String message,
                        Instant date) throws Exception {
        Path file = repo.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
        Path msg = repo.resolve(".git/TEST_COMMIT_MSG");
        Files.writeString(msg, message, java.nio.charset.StandardCharsets.UTF_8);
        GitRemoteOps.GitResult r = ops.addCommit(repo.toString(), List.of(relPath.replace('\\', '/')),
                msg, "测试用户", "tester@devmind.local", date);
        Files.deleteIfExists(msg);
        assertTrue(r.ok(), () -> "addCommit 失败: " + r.output());
    }

    @Test
    void initRepo幂等() {
        Path dir = tmp.resolve("r1");
        assertTrue(ops.initRepo(dir.toString(), "main").ok());
        assertTrue(ops.initRepo(dir.toString(), "main").ok());
        assertTrue(Files.exists(dir.resolve(".git")));
    }

    @Test
    void addCommit中文message与trailer无乱码() throws Exception {
        Path repo = initRepo("r2");
        commit(repo, "u/daily/2026-10-12.md", "日报内容", "worklog: daily 2026-10-12\n\nDevMind-Source: ai\nDevMind-Session: s-1\n", null);
        List<GitRemoteOps.PathCommit> hits = ops.lastCommitByPath(repo.toString(),
                List.of("u/daily/2026-10-12.md"), 10);
        assertEquals(1, hits.size());
        GitRemoteOps.PathCommit pc = hits.get(0);
        assertEquals("worklog: daily 2026-10-12", pc.subject());
        assertEquals("ai", pc.trailers().get("DevMind-Source"));
        assertEquals("s-1", pc.trailers().get("DevMind-Session"));
        assertNotNull(pc.committedAt());
        assertEquals(pc.committedAt(), pc.firstCommittedAt());
    }

    @Test
    void addCommit日期回填与幂等跳过() throws Exception {
        Path repo = initRepo("r3");
        Instant backfill = Instant.parse("2026-09-01T08:30:00Z");
        commit(repo, "u/daily/2026-09-01.md", "v1", "worklog: daily 2026-09-01\n\nDevMind-Source: migration\n", backfill);
        List<GitRemoteOps.PathCommit> hits = ops.lastCommitByPath(repo.toString(),
                List.of("u/daily/2026-09-01.md"), 10);
        assertEquals(backfill, hits.get(0).committedAt());

        // 同内容再提交 → nothing to commit（幂等跳过）
        Path file = repo.resolve("u/daily/2026-09-01.md");
        Path msg = repo.resolve(".git/TEST_COMMIT_MSG");
        Files.writeString(msg, "worklog: daily 2026-09-01\n", java.nio.charset.StandardCharsets.UTF_8);
        GitRemoteOps.GitResult r = ops.addCommit(repo.toString(), List.of("u/daily/2026-09-01.md"),
                msg, "测试用户", "tester@devmind.local", null);
        assertTrue(r.ok());
        assertTrue(r.output().contains("nothing to commit"), () -> "意外输出: " + r.output());
        assertEquals(1, ops.lastCommitByPath(repo.toString(), List.of("u/daily/2026-09-01.md"), 10).size());
    }

    @Test
    void lastCommitByPath批量多路径与首末次() throws Exception {
        Path repo = initRepo("r4");
        Instant t1 = Instant.parse("2026-10-01T01:00:00Z");
        Instant t2 = Instant.parse("2026-10-02T01:00:00Z");
        Instant t3 = Instant.parse("2026-10-03T01:00:00Z");
        commit(repo, "u/daily/2026-10-01.md", "a", "worklog: daily 2026-10-01\n", t1);
        commit(repo, "u/daily/2026-10-01.md", "b", "worklog: daily 2026-10-01 改\n", t2);
        commit(repo, "u/daily/2026-10-02.md", "c", "worklog: daily 2026-10-02\n", t3);
        List<GitRemoteOps.PathCommit> hits = ops.lastCommitByPath(repo.toString(), List.of(
                "u/daily/2026-10-01.md", "u/daily/2026-10-02.md", "u/daily/2099-01-01.md"), 50);
        assertEquals(2, hits.size());
        GitRemoteOps.PathCommit p1 = hits.stream().filter(h -> h.path().endsWith("10-01.md")).findFirst().orElseThrow();
        assertEquals(t2, p1.committedAt());
        assertEquals(t1, p1.firstCommittedAt());
        GitRemoteOps.PathCommit p2 = hits.stream().filter(h -> h.path().endsWith("10-02.md")).findFirst().orElseThrow();
        assertEquals(t3, p2.committedAt());
        assertEquals(t3, p2.firstCommittedAt());
    }

    @Test
    void merge冲突制造与abort() throws Exception {
        // bare origin + 两个 clone 双侧改同一文件制造冲突
        Path origin = tmp.resolve("origin.git");
        runGit(tmp, "init", "--bare", "-b", "main", origin.toString());
        String originUrl = "file://" + origin.toString().replace('\\', '/');
        // 播种初始提交（否则双侧 root commit 是 unrelated histories，制造不出冲突）
        Path seed = tmp.resolve("seed");
        assertTrue(ops.cloneRepo(originUrl, null, seed.toString(), null, null).ok());
        commit(seed, "README.md", "seed", "init\n", null);
        assertTrue(ops.pushBranch(seed.toString(), "main", originUrl, null).ok());

        Path a = tmp.resolve("a");
        assertTrue(ops.cloneRepo(originUrl, null, a.toString(), null, null).ok());
        // b 必须在 a push 之前 clone（否则 b 自带 a 的提交，退化为快进制造不出冲突）
        Path b = tmp.resolve("b");
        assertTrue(ops.cloneRepo(originUrl, null, b.toString(), null, null).ok());

        commit(a, "u/daily/2026-10-05.md", "平台版", "c1\n", null);
        assertTrue(ops.pushBranch(a.toString(), "main", originUrl, null).ok());

        commit(b, "u/daily/2026-10-05.md", "远端版", "c2\n", null);
        // b 推送被拒（远端领先，non-ff）
        assertFalse(ops.pushBranch(b.toString(), "main", originUrl, null).ok());

        // b 侧 fetch + merge → 同文件双侧改动冲突
        assertTrue(ops.fetchAllRefs(b.toString(), originUrl, null).ok());
        GitRemoteOps.GitResult merge = ops.mergeNoEdit(b.toString(), "origin/main");
        assertFalse(merge.ok(), () -> "应冲突: " + merge.output());
        assertTrue(Files.exists(b.resolve(".git/MERGE_HEAD")), () -> "merge output: " + merge.output());
        assertTrue(ops.mergeAbort(b.toString()).ok());
        assertFalse(Files.exists(b.resolve(".git/MERGE_HEAD")));
    }

    @Test
    void refspecPush远端分支不同名() throws Exception {
        Path origin = tmp.resolve("o2.git");
        runGit(tmp, "init", "--bare", "-b", "main", origin.toString());
        Path repo = initRepo("r6");
        commit(repo, "u/daily/2026-10-06.md", "x", "c\n", null);
        String url = "file://" + origin.toString().replace('\\', '/');
        assertTrue(ops.pushBranch(repo.toString(), "main", "backup", url, null).ok());
        GitRemoteOps.GitResult ls = ops.lsRemote(url, null, "refs/heads/backup");
        assertTrue(ls.ok() && !ls.output().isBlank(), () -> "远端分支不存在: " + ls.output());
    }

    private static void runGit(Path cwd, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + out);
        }
    }
}
