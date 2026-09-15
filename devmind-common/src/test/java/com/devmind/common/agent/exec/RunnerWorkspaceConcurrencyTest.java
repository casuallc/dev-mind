package com.devmind.common.agent.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-34 FR-04 {@link RunnerWorkspace} 同克隆缓存互斥断言；CAP-42 起缓存按用户隔离
 * （&lt;proj&gt;/&lt;owner&gt;/main）：不同用户并发 prepare 同项目各自成功；
 * 同用户并发 prepare 同项目（不同会话分支）→ 恰好一个成功，另一个占用冲突。
 */
class RunnerWorkspaceConcurrencyTest {

    @TempDir
    Path tmp;

    @Test
    void concurrentPrepareDifferentUsersBothSucceed() throws Exception {
        Path origin = seedOrigin();

        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<RunnerWorkspace.RepoCtx> results = new CopyOnWriteArrayList<>();

        Runnable task = () -> {
            String owner = Thread.currentThread().getName().equals("t0") ? "alice" : "bob";
            try {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                results.add(ws.prepare("s1", "proj1", owner, new RunnerWorkspace.RepoSpec(
                        origin.toUri().toString(), "main", "feature/s1", "")));
            } catch (Throwable t) {
                failures.add(t);
            }
        };
        Thread t0 = Thread.ofVirtual().name("t0").start(task);
        Thread t1 = Thread.ofVirtual().name("t1").start(task);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        t0.join(60_000);
        t1.join(60_000);

        assertTrue(failures.isEmpty(), "不同用户并发 prepare 不应失败: " + failures);
        assertEquals(2, results.size());
        for (RunnerWorkspace.RepoCtx ctx : results) {
            assertTrue(Files.isDirectory(ctx.sessionDir()), "固定 worktree 应存在: " + ctx.sessionDir());
            assertTrue(Files.exists(ctx.sessionDir().resolve("README.md")));
        }
        // 目录各自独立（按用户隔离）
        assertTrue(results.get(0).sessionDir() != results.get(1).sessionDir()
                && !results.get(0).sessionDir().equals(results.get(1).sessionDir()));
    }

    @Test
    void concurrentPrepareSameUserExactlyOneWins() throws Exception {
        Path origin = seedOrigin();

        RunnerWorkspace ws = new RunnerWorkspace(tmp.resolve("workspaces"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<RunnerWorkspace.RepoCtx> results = new CopyOnWriteArrayList<>();

        Runnable task = () -> {
            String sid = Thread.currentThread().getName().equals("t0") ? "s-a" : "s-b";
            try {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                results.add(ws.prepare(sid, "proj1", "alice", new RunnerWorkspace.RepoSpec(
                        origin.toUri().toString(), "main", "feature/" + sid, "")));
            } catch (Throwable t) {
                failures.add(t);
            }
        };
        Thread t0 = Thread.ofVirtual().name("t0").start(task);
        Thread t1 = Thread.ofVirtual().name("t1").start(task);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        t0.join(60_000);
        t1.join(60_000);

        // 同 (项目, 用户) 唯一活跃工作区：恰好一个成功，另一个占用冲突
        assertEquals(1, results.size(), "结果: " + results + " 失败: " + failures);
        assertEquals(1, failures.size());
        assertTrue(String.valueOf(failures.get(0).getMessage()).contains("占用"),
                String.valueOf(failures.get(0).getMessage()));
    }

    private Path seedOrigin() throws Exception {
        Path origin = tmp.resolve("origin.git");
        git(tmp, "init", "--bare", "-b", "main", origin.toString());
        Path seed = tmp.resolve("seed");
        git(tmp, "clone", origin.toString(), seed.toString());
        Files.writeString(seed.resolve("README.md"), "hello");
        git(seed, "add", ".");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        git(seed, "push", "origin", "main");
        return origin;
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
