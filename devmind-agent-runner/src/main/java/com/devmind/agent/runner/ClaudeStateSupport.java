package com.devmind.agent.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CAP-51 FR-12：claude CLI 本地状态目录与保留期。
 *
 * <p>CAP-51 把工作树粒度从「每用户」改到「每需求」后，claude 自己的状态目录会跟着 cwd 走
 * （transcript 落 {@code <configDir>/projects/<slug(cwd)>/}，归属键是启动 cwd）：目录数随需求数
 * 增长，且需求工作树保留 30 天，于是有两件事必须显式管住——</p>
 *
 * <ol>
 *   <li><b>配置目录专属化</b>：默认落 {@code {workspaceRoot}/../claude-config} 而不是节点用户的
 *       {@code ~/.claude}，使平台会话的清理策略与用户的个人 Claude Code 记录隔离。
 *       服务化部署（LocalSystem/root）下仍应显式配置为已登录目录。</li>
 *   <li><b>保留期显式设置</b>：claude 的 {@code cleanupPeriodDays} <b>默认 30 天</b>（{@code 0} 被拒绝），
 *       启动时静默清扫超期 transcript——静置超期的需求工作树会「工作树还在、会话续不上」。
 *       该清扫遍历整机所有 {@code projects/}，所以只能在 runner 专属配置里设，不能放项目级。
 *       已有值一律不覆盖（尊重人工设置），但小于工作树寿命时告警。</li>
 * </ol>
 */
final class ClaudeStateSupport {

    private static final Logger log = LoggerFactory.getLogger(ClaudeStateSupport.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * transcript 保留天数（工作树 GC 阈值的数倍）：需求工作树默认留 30 天，
     * 保留期必须覆盖它，否则回收前的会话就已经续不上。
     */
    static final int CLEANUP_PERIOD_DAYS = 180;

    private ClaudeStateSupport() {
    }

    /**
     * claude 状态目录的归属键：cwd 绝对路径的非 [a-zA-Z0-9] 字符全替换为 '-'
     * （与 claude 实现一致；官方另有超 200 字符截断+短哈希，平台路径不会触及，不实现）。
     */
    static String slug(Path cwd) {
        return cwd.toAbsolutePath().normalize().toString().replaceAll("[^a-zA-Z0-9]", "-");
    }

    /**
     * CAP-53 FR-04 resume transcript 迁移（best-effort）：cwd 从需求工作树上抬到
     * 「项目+用户」根后，存量会话的 transcript 仍在旧 slug（工作树路径）目录下，
     * {@code --resume} 在新 cwd 找不到即续接失败。带 resumeSessionId 的 launch 前调用：
     * 旧 slug 目录存在且新目录缺该会话 jsonl → 整体复制（不覆盖既有文件）——jsonl、
     * 同名 sidecar 目录、memory/ 一并随迁（需求粒度时期积累的 memory 借此并回项目级）。
     * 复制而非移动（旧目录由 claude 自身保留期回收）；任何失败只告警，不阻断 launch。
     */
    static void migrateTranscripts(Path configDir, Path oldCwd, Path newCwd, String resumeSessionId) {
        if (resumeSessionId == null || resumeSessionId.isBlank()
                || oldCwd == null || newCwd == null || oldCwd.equals(newCwd)) {
            return;
        }
        Path oldDir = configDir.resolve("projects").resolve(slug(oldCwd));
        Path newDir = configDir.resolve("projects").resolve(slug(newCwd));
        try {
            if (!Files.isDirectory(oldDir)
                    || Files.isRegularFile(newDir.resolve(resumeSessionId + ".jsonl"))) {
                return; // 无旧状态，或已在新归属下（幂等）
            }
            Files.createDirectories(newDir);
            try (var walk = Files.walk(oldDir)) {
                for (Path src : walk.toList()) {
                    Path dst = newDir.resolve(oldDir.relativize(src).toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else if (!Files.exists(dst)) {
                        Files.copy(src, dst);
                    }
                }
            }
            log.info("claude 状态已随 cwd 上抬迁移: {} -> {}", oldDir, newDir);
        } catch (Exception e) {
            log.warn("claude 状态迁移失败（resume 可能续不上，不阻断会话）: {} -> {} err={}",
                    oldDir, newDir, e.getMessage());
        }
    }

    /**
     * claude 配置目录：配置非空用配置值；空 = 平台专属目录 {@code {workspaceRoot}/../claude-config}
     * （与 worklog/工作区同级的 runner 私有地盘，不碰节点用户的 {@code ~/.claude}）。
     */
    static Path resolveConfigDir(RunnerConfig config) {
        String configured = config.claudeConfigDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path root = config.workspaceRoot().toAbsolutePath().normalize();
        Path parent = root.getParent();
        return (parent == null ? root : parent).resolve("claude-config").normalize();
    }

    /**
     * 启动时落一次保留期设置（best-effort，任何失败都只告警——claude 用默认值也能跑，
     * 只是静置超期的会话会续不上，这个信息必须让运维看见）。
     */
    static void ensureRetention(Path configDir, int worktreeGcDays) {
        Path settings = configDir.resolve("settings.json");
        try {
            Files.createDirectories(configDir);
            ObjectNode node;
            if (Files.isRegularFile(settings)) {
                JsonNode parsed;
                try {
                    parsed = MAPPER.readTree(Files.readString(settings, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    log.warn("claude settings.json 解析失败，保留期未设置（请手工补 cleanupPeriodDays={}）: {} err={}",
                            CLEANUP_PERIOD_DAYS, settings, e.getMessage());
                    return;
                }
                node = parsed instanceof ObjectNode o ? o : MAPPER.createObjectNode();
            } else {
                node = MAPPER.createObjectNode();
            }
            JsonNode existing = node.get("cleanupPeriodDays");
            if (existing != null && !existing.isNull()) {
                int days = existing.asInt(-1);
                if (days >= 0 && days < worktreeGcDays) {
                    log.warn("claude 保留期 cleanupPeriodDays={} 小于需求工作树 GC 阈值 {} 天"
                                    + "（且清扫遍历整机所有 projects/），静置的工作树会先失去 resume 能力: {}",
                            days, worktreeGcDays, settings);
                } else {
                    log.info("claude 保留期已设置（cleanupPeriodDays={}）: {}", days, settings);
                }
                return;
            }
            node.put("cleanupPeriodDays", CLEANUP_PERIOD_DAYS);
            Files.writeString(settings,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n",
                    StandardCharsets.UTF_8);
            log.info("已为平台专属 claude 配置设置保留期: cleanupPeriodDays={} file={}",
                    CLEANUP_PERIOD_DAYS, settings);
        } catch (Exception e) {
            log.warn("claude 保留期设置失败（不影响会话，但静置超期会话可能续不上）: {} err={}",
                    settings, e.getMessage());
        }
    }
}
