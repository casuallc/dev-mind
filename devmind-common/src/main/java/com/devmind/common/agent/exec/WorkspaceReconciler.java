package com.devmind.common.agent.exec;

import com.devmind.common.agent.runtime.ProcessHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CAP-34 FR-04 runner 重启现场对账：扫描工作区里的存量会话目录
 * （&lt;root&gt;/&lt;projectId&gt;/sessions/&lt;sid&gt; 与 &lt;root&gt;/_chat/&lt;sid&gt;），
 * 按目录内 {@value #PID_FILE} 文件（register 时写入：pid + 进程启动时刻）判定孤儿 claude 进程并整树回收；
 * 进程已不在的目录登记为无主目录（<b>不删</b>——超龄删除是 FR-05 工作区 GC 的职责）。
 *
 * <p>PID 复用防护：要求进程 startInstant 与文件记录时刻误差 &lt; {@value #START_SKEW_MS}ms，
 * 否则视为无关进程，只登记不杀。legacy 目录（launch 无 repo/kind 块的 project 映射路径）
 * 不写 pid 文件，不参与对账。</p>
 */
public class WorkspaceReconciler {

    public static final String PID_FILE = ".runner-pid";
    private static final long START_SKEW_MS = 5_000;
    private static final Logger log = LoggerFactory.getLogger(WorkspaceReconciler.class);
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9._-]+");

    /** 被回收的孤儿会话进程。 */
    public record OrphanedSession(String sessionId, Path dir, long pid) {
    }

    /** 对账结果：reaped=已整树回收的孤儿进程；ownerlessDirs=进程已不在的无主目录（移交 GC）。 */
    public record ReconcileReport(List<OrphanedSession> reaped, List<Path> ownerlessDirs) {
    }

    private final Path workspaceRoot;

    public WorkspaceReconciler(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /** register 时调用：把会话进程 pid + 启动时刻落到会话目录（best-effort，失败只告警）。 */
    public static void writePidFile(Path sessionDir, Process process) {
        try {
            long startEpoch = process.toHandle().info().startInstant()
                    .map(Instant::toEpochMilli).orElse(-1L);
            Files.writeString(sessionDir.resolve(PID_FILE),
                    process.pid() + "\n" + startEpoch + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("pid 文件写入失败（该会话目录将不参与重启对账）: dir={} err={}", sessionDir, e.getMessage());
        }
    }

    /** 进程退出收口时调用：清除 pid 文件（best-effort）。 */
    public static void clearPidFile(Path sessionDir) {
        if (sessionDir == null) {
            return;
        }
        try {
            Files.deleteIfExists(sessionDir.resolve(PID_FILE));
        } catch (IOException e) {
            log.debug("pid 文件清理失败: dir={} err={}", sessionDir, e.getMessage());
        }
    }

    /** 启动扫描：回收孤儿进程，登记无主目录。 */
    public ReconcileReport reconcile() {
        List<OrphanedSession> reaped = new ArrayList<>();
        List<Path> ownerless = new ArrayList<>();
        for (Path dir : sessionDirs()) {
            String sid = dir.getFileName().toString();
            Path pidFile = dir.resolve(PID_FILE);
            if (!Files.isRegularFile(pidFile)) {
                ownerless.add(dir);
                continue;
            }
            Long pid = readLivePid(pidFile);
            if (pid != null && ProcessHelper.killTreeByPid(pid)) {
                reaped.add(new OrphanedSession(sid, dir, pid));
                log.info("回收孤儿会话进程: session={} pid={} dir={}", sid, pid, dir);
            } else {
                ownerless.add(dir);
            }
        }
        log.info("工作区对账完成: 回收孤儿进程 {} 个，无主目录 {} 个", reaped.size(), ownerless.size());
        return new ReconcileReport(reaped, ownerless);
    }

    /** 读取 pid 文件并校验指向的进程就是当年那个（存活 + 启动时刻吻合），不吻合/已死返回 null。 */
    private Long readLivePid(Path pidFile) {
        try {
            List<String> lines = Files.readAllLines(pidFile, StandardCharsets.UTF_8);
            long pid = Long.parseLong(lines.get(0).strip());
            long startEpoch = lines.size() > 1 ? Long.parseLong(lines.get(1).strip()) : -1L;
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return null;
            }
            if (startEpoch > 0) {
                Optional<Instant> started = handle.get().info().startInstant();
                // startInstant 不可知时保守不杀（防 PID 复用误杀无辜进程）
                if (started.isEmpty() || Math.abs(started.get().toEpochMilli() - startEpoch) >= START_SKEW_MS) {
                    return null;
                }
            }
            return pid;
        } catch (Exception e) {
            log.debug("pid 文件不可解析: {} err={}", pidFile, e.getMessage());
            return null;
        }
    }

    /** 存量会话目录：<root>/<projectId>/sessions/<sid>（跳过 _chat 桶）+ <root>/_chat/<sid>。 */
    private List<Path> sessionDirs() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(workspaceRoot)) {
            return out;
        }
        try (Stream<Path> projects = Files.list(workspaceRoot)) {
            for (Path proj : projects.filter(Files::isDirectory).toList()) {
                if ("_chat".equals(proj.getFileName().toString())) {
                    continue;
                }
                collectSubDirs(proj.resolve("sessions"), out);
            }
        } catch (IOException e) {
            log.warn("工作区扫描失败: {} err={}", workspaceRoot, e.getMessage());
        }
        collectSubDirs(workspaceRoot.resolve("_chat"), out);
        return out;
    }

    private void collectSubDirs(Path parent, List<Path> out) {
        if (!Files.isDirectory(parent)) {
            return;
        }
        try (Stream<Path> s = Files.list(parent)) {
            s.filter(Files::isDirectory)
                    .filter(p -> SAFE_ID.matcher(p.getFileName().toString()).matches())
                    .forEach(out::add);
        } catch (IOException e) {
            log.warn("目录扫描失败: {} err={}", parent, e.getMessage());
        }
    }
}
