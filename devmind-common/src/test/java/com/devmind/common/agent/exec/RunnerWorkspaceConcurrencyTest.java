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
 * CAP-34 FR-04 {@link RunnerWorkspace} 同克隆缓存互斥断言：两个虚拟线程并发 prepare
 * 同 projectId（共享 &lt;root&gt;/&lt;projectId&gt;/main 缓存），不加锁时并发 fetch/worktree
 * 会互踩失败；加锁后两者都应成功且会话目录各自独立。
 */
class RunnerWorkspaceConcurrencyTest {

    @TempDir
    Path tmp;

    @Test
    void concurrentPrepareSameProjectBothSucceed() throws Exception {
        Path origin = tmp.resolve("origin.git");
        git(tmp, "init", "--bare", "-b", "main", origin.toString());
        Path seed = tmp.resolve("seed");
        git(tmp, "clone", origin.toString(), seed.toString());
        Files.writeString(seed.resolve("README.md"), "hello");
        git(seed, "add", ".");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init");
        git(seed, "push", "origin", "main");

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
                results.add(ws.prepare(sid, "proj1", new RunnerWorkspace.RepoSpec(
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

        assertTrue(failures.isEmpty(), "并发 prepare 不应失败: " + failures);
        assertEquals(2, results.size());
        for (RunnerWorkspace.RepoCtx ctx : results) {
            assertTrue(Files.isDirectory(ctx.sessionDir()), "会话目录应存在: " + ctx.sessionDir());
            assertTrue(Files.exists(ctx.sessionDir().resolve("README.md")));
        }
        // 目录各自独立
        assertTrue(results.get(0).sessionDir() != results.get(1).sessionDir()
                && !results.get(0).sessionDir().equals(results.get(1).sessionDir()));
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
