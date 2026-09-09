package com.devmind.common.agent.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CAP-34 FR-05 会话工作区超龄 GC：清理 &lt;root&gt;/&lt;projectId&gt;/sessions/&lt;sid&gt;
 * 与 &lt;root&gt;/_chat/&lt;sid&gt; 里 N 天未活动的残留目录（正常结束已由 finalizer 收口，
 * GC 只兜 runner 强杀/崩溃留下的孤儿目录）。
 *
 * <p>删除判定（全部满足才删，<b>宁跳不错删</b>）：</p>
 * <ol>
 *   <li>不在活跃会话清单（运行中的会话进程持有该目录）；</li>
 *   <li>无可信存活 pid 文件（{@link WorkspaceReconciler#hasLivePidFile}）；</li>
 *   <li>目录 mtime 距今 &gt; gcDays；</li>
 *   <li>repo 会话目录：会话分支（feature/&lt;sid&gt;，CAP 平台约定）在缓存库不存在（无提交可丢），
 *       或经 {@code git ls-remote} 确认分支已在远端（已推送）。ls-remote 不带 token——
 *       私有仓库探测失败一律保守跳过（正常 finish 已删目录，GC 兜底的漏网目录可人工清理）。</li>
 * </ol>
 * chat 沙箱只做 1~3。
 */
public class WorkspaceGc {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceGc.class);
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9._-]+");
    private static final long LS_REMOTE_TIMEOUT_SEC = 30;

    public record GcReport(int scanned, int deleted, long freedBytes, List<String> skipped) {
    }

    private final Path workspaceRoot;

    public WorkspaceGc(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /** 跑一轮 GC。activeSessionIds = runner 当前存活会话（RunnerSessionRegistry.activeSessionIds()）。 */
    public GcReport run(int gcDays, Set<String> activeSessionIds) {
        int scanned = 0;
        int deleted = 0;
        long freed = 0;
        List<String> skipped = new ArrayList<>();
        long cutoffMillis = System.currentTimeMillis() - gcDays * 24L * 3600 * 1000;

        for (Candidate c : candidates()) {
            scanned++;
            String sid = c.dir().getFileName().toString();
            if (activeSessionIds.contains(sid)) {
                skipped.add(sid + ": 活跃会话");
                continue;
            }
            if (WorkspaceReconciler.hasLivePidFile(c.dir())) {
                skipped.add(sid + ": 会话进程仍存活（pid 文件）");
                continue;
            }
            try {
                if (Files.getLastModifiedTime(c.dir()).toMillis() > cutoffMillis) {
                    continue; // 未超龄，静默跳过（正常路径）
                }
            } catch (IOException e) {
                skipped.add(sid + ": mtime 不可读");
                continue;
            }
            if (c.repoCacheDir() != null && !branchPushed(c.repoCacheDir(), "feature/" + sid)) {
                skipped.add(sid + ": 会话分支未确认推送远端，保留待人工处理");
                continue;
            }
            long size = sizeOf(c.dir());
            if (deleteRecursively(c.dir())) {
                deleted++;
                freed += size;
                log.info("GC 删除超龄会话目录: {}（释放 {} 字节）", c.dir(), size);
            } else {
                skipped.add(sid + ": 删除失败");
            }
        }
        log.info("工作区 GC 完成: 扫描 {} 删除 {} 释放 {} 字节 跳过(带原因) {}", scanned, deleted, freed, skipped.size());
        return new GcReport(scanned, deleted, freed, skipped);
    }

    /** 工作区总占用（hello/heartbeat 的 workspaceBytes）。 */
    public long usageBytes() {
        return sizeOf(workspaceRoot);
    }

    /**
     * CAP-36 构建工作区（&lt;proj&gt;/builds/&lt;id&gt;）超龄清理：无分支/push 语义，
     * 超龄即删（仍在执行中的 workspaceId 跳过）。构建链结束后工作区无保留价值，
     * 保留窗口（runner 配置 buildGcHours，默认 24h）仅供失败排查。删除后 best-effort
     * {@code git worktree prune} 清缓存库的 worktree 元数据。
     */
    public GcReport sweepBuilds(long maxAgeMs, Set<String> activeBuildIds) {
        int scanned = 0;
        int deleted = 0;
        long freed = 0;
        List<String> skipped = new ArrayList<>();
        long cutoffMillis = System.currentTimeMillis() - maxAgeMs;
        if (!Files.isDirectory(workspaceRoot)) {
            return new GcReport(0, 0, 0, List.of());
        }
        try (Stream<Path> projects = Files.list(workspaceRoot)) {
            for (Path proj : projects.filter(Files::isDirectory).toList()) {
                Path builds = proj.resolve("builds");
                if (!Files.isDirectory(builds)) {
                    continue;
                }
                try (Stream<Path> s = Files.list(builds)) {
                    for (Path dir : s.filter(Files::isDirectory)
                            .filter(p -> SAFE_ID.matcher(p.getFileName().toString()).matches()).toList()) {
                        scanned++;
                        String id = dir.getFileName().toString();
                        if (activeBuildIds.contains(id)) {
                            skipped.add(id + ": 构建进行中");
                            continue;
                        }
                        try {
                            if (Files.getLastModifiedTime(dir).toMillis() > cutoffMillis) {
                                continue; // 未超龄
                            }
                        } catch (IOException e) {
                            skipped.add(id + ": mtime 不可读");
                            continue;
                        }
                        long size = sizeOf(dir);
                        if (deleteRecursively(dir)) {
                            deleted++;
                            freed += size;
                            log.info("GC 删除超龄构建工作区: {}（释放 {} 字节）", dir, size);
                            git(proj.resolve("main"), "worktree", "prune"); // best-effort 清元数据
                        } else {
                            skipped.add(id + ": 删除失败");
                        }
                    }
                } catch (IOException e) {
                    log.warn("GC 扫描构建目录失败: {} err={}", builds, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("GC 扫描失败: {} err={}", workspaceRoot, e.getMessage());
        }
        if (scanned > 0) {
            log.info("构建工作区 GC 完成: 扫描 {} 删除 {} 释放 {} 字节 跳过(带原因) {}", scanned, deleted, freed, skipped.size());
        }
        return new GcReport(scanned, deleted, freed, skipped);
    }

    /** 候选目录：repo 会话（带缓存库路径供分支判定）+ chat 沙箱。 */
    private record Candidate(Path dir, Path repoCacheDir) {
    }

    private List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        if (!Files.isDirectory(workspaceRoot)) {
            return out;
        }
        try (Stream<Path> projects = Files.list(workspaceRoot)) {
            for (Path proj : projects.filter(Files::isDirectory).toList()) {
                if ("_chat".equals(proj.getFileName().toString())) {
                    continue;
                }
                Path sessions = proj.resolve("sessions");
                if (!Files.isDirectory(sessions)) {
                    continue;
                }
                Path cacheDir = proj.resolve("main"); // 单库缓存；多库分支判定按子库各自缓存（见下）
                try (Stream<Path> s = Files.list(sessions)) {
                    s.filter(Files::isDirectory)
                            .filter(p -> SAFE_ID.matcher(p.getFileName().toString()).matches())
                            .forEach(dir -> out.add(new Candidate(dir, resolveCacheDir(proj, cacheDir, dir))));
                } catch (IOException e) {
                    log.warn("GC 扫描失败: {} err={}", sessions, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("GC 扫描失败: {} err={}", workspaceRoot, e.getMessage());
        }
        Path chat = workspaceRoot.resolve("_chat");
        if (Files.isDirectory(chat)) {
            try (Stream<Path> s = Files.list(chat)) {
                s.filter(Files::isDirectory)
                        .filter(p -> SAFE_ID.matcher(p.getFileName().toString()).matches())
                        .forEach(dir -> out.add(new Candidate(dir, null)));
            } catch (IOException e) {
                log.warn("GC 扫描失败: {} err={}", chat, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 多库会话目录 sessions/&lt;sid&gt;/&lt;repoName&gt; 的缓存库在 &lt;proj&gt;/&lt;repoName&gt;/main：
     * 只要任一子库存在未推送分支即保守保留（返回该子库缓存路径）；单库直接返回 &lt;proj&gt;/main。
     */
    private Path resolveCacheDir(Path proj, Path singleCacheDir, Path sessionDir) {
        // worktree 的 .git 是文件（gitdir 指针）不是目录，用 exists 判定
        if (Files.exists(sessionDir.resolve(".git"))) {
            return singleCacheDir; // 单库：会话目录本身是 worktree
        }
        try (Stream<Path> s = Files.list(sessionDir)) {
            for (Path sub : s.filter(Files::isDirectory).toList()) {
                if (Files.exists(sub.resolve(".git"))) {
                    Path multiCache = proj.resolve(sub.getFileName().toString()).resolve("main");
                    if (Files.isDirectory(multiCache.resolve(".git"))) {
                        return multiCache; // 取首个子库缓存做分支判定（其余子库同分支名，判定同结论）
                    }
                }
            }
        } catch (IOException e) {
            log.debug("多库会话目录扫描失败: {} err={}", sessionDir, e.getMessage());
        }
        return singleCacheDir;
    }

    /**
     * 会话分支是否已确认推送远端：本地无此分支 = 无提交可丢 → true；
     * 有则 ls-remote origin 确认远端存在同名分支。探测失败/远端无 → false（保守保留）。
     */
    private boolean branchPushed(Path cacheDir, String branch) {
        if (!Files.isDirectory(cacheDir.resolve(".git"))) {
            return true; // 缓存库都不在，无从判定也无提交可丢（目录是裸残留）
        }
        String localRef = git(cacheDir, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch);
        if (localRef == null) {
            return true; // 本地无会话分支
        }
        String remote = git(cacheDir, "ls-remote", "origin", "refs/heads/" + branch);
        return remote != null && remote.contains("refs/heads/" + branch);
    }

    /** 跑 git（无 token，纯只读探测）；exit!=0 或异常返回 null。 */
    private static String git(Path cwd, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", cwd.toString()));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            var outFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            if (!p.waitFor(LS_REMOTE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? outFuture.join() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static long sizeOf(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (IOException e) {
            log.warn("GC 删除失败: {} err={}", dir, e.getMessage());
            return false;
        }
    }
}
