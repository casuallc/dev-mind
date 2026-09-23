package com.devmind.agent.runner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CAP-57 受管进程注册表（runner 侧，内存 + proc.json 双写）。
 *
 * <p>与 {@link RunnerSessionRegistry} 的关键语义分岔：受管进程（分类边车等服务实例）的设计目标
 * 是<b>比 runner 活得久</b>——runner 重启/升级不杀它们（shutdown hook 刻意不含 proc kill），
 * 重启后靠 {@code <workspaceRoot>/classify/run/inst-<id>/proc.json} 里记的 pid 对账回来
 * （pid 存活校验 {@link ProcessHandle#of(long)}，先例：CAP-34 WorkspaceReconciler）。</p>
 */
public class ProcRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProcRegistry.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 一条受管进程记录（pidFile 为绝对路径，回写/删除 proc.json 用） */
    public record Entry(String instanceId, long pid, String command, Path procFile) {
    }

    private final Path runRoot; // <workspaceRoot>/classify/run
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ProcRegistry(Path classifyRoot) {
        this.runRoot = classifyRoot.resolve("run");
    }

    /** runner 启动对账：扫 run/inst-{@literal *}/proc.json，pid 活着的登记回来，死掉的清尸 */
    public int reconcile() {
        if (!Files.isDirectory(runRoot)) {
            return 0;
        }
        int restored = 0;
        try (var stream = Files.list(runRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path procFile = dir.resolve("proc.json");
                if (!Files.isRegularFile(procFile)) {
                    continue;
                }
                try {
                    var node = MAPPER.readTree(Files.readString(procFile, StandardCharsets.UTF_8));
                    String instanceId = node.path("instanceId").asText("");
                    long pid = node.path("pid").asLong(-1);
                    String command = node.path("command").asText("");
                    if (instanceId.isBlank() || pid <= 0) {
                        Files.deleteIfExists(procFile);
                        continue;
                    }
                    Optional<ProcessHandle> handle = ProcessHandle.of(pid);
                    if (handle.isPresent() && handle.get().isAlive()) {
                        entries.put(instanceId, new Entry(instanceId, pid, command, procFile));
                        restored++;
                    } else {
                        Files.deleteIfExists(procFile);
                        log.info("对账清理死亡受管进程记录: instance={} pid={}", instanceId, pid);
                    }
                } catch (Exception e) {
                    log.warn("受管进程记录读取失败（跳过）: {} err={}", procFile, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("受管进程对账失败（按空表继续）: {}", e.getMessage());
        }
        return restored;
    }

    /** 登记并回写 proc.json（启动成功后调用） */
    public void register(String instanceId, long pid, String command) {
        try {
            Path dir = runRoot.resolve("inst-" + instanceId);
            Files.createDirectories(dir);
            Path procFile = dir.resolve("proc.json");
            String json = "{\"instanceId\":\"" + instanceId + "\",\"pid\":" + pid
                    + ",\"command\":" + MAPPER.writeValueAsString(command == null ? "" : command) + "}";
            Files.writeString(procFile, json, StandardCharsets.UTF_8);
            entries.put(instanceId, new Entry(instanceId, pid, command, procFile));
        } catch (Exception e) {
            // 回写失败不阻断启动——进程已拉起；代价是 runner 重启后对账不到它（status 退化为 pidfile 探测）
            log.warn("受管进程登记回写失败: instance={} err={}", instanceId, e.getMessage());
            entries.put(instanceId, new Entry(instanceId, pid, command, null));
        }
    }

    /** 注销并删 proc.json（stop 杀净后调用） */
    public void unregister(String instanceId) {
        Entry e = entries.remove(instanceId);
        if (e != null && e.procFile() != null) {
            try {
                Files.deleteIfExists(e.procFile());
            } catch (Exception ex) {
                log.warn("受管进程记录删除失败: {} err={}", e.procFile(), ex.getMessage());
            }
        }
    }

    public Optional<Entry> entryOf(String instanceId) {
        return Optional.ofNullable(entries.get(instanceId));
    }
}
