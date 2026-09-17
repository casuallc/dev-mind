package com.devmind.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * CAP-44 FR-02 存量迁移：扁平经验条目（scope/project_id）→ 知识库容器归属。
 * 建「全局经验库」（global+FULL）；按存量条目 project_id 各建「XX 项目经验库」（project+FULL）；
 * 回填 entries.kb_id 及新列默认值（source/index_status）。
 * 幂等：kb_id 已回填的条目跳过、库按 (scope, project_id, name) 查重；任何异常只记日志不阻断启动。
 * 旧 scope/project_id 列保留不删（防 rollback 丢数据）。
 */
@Component
public class KnowledgeBaseMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseMigration.class);

    public static final String GLOBAL_KB_NAME = "全局经验库";

    private final JdbcTemplate jdbc;

    public KnowledgeBaseMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("知识库容器迁移失败（不阻断启动，可重启重试）: {}", e.getMessage());
        }
    }

    private void migrate() {
        if (!tableExists("knowledge_entries") || !tableExists("knowledge_bases")) {
            return;
        }
        // 新列默认值兜底（存量行 JPA 字段初始值不生效）
        jdbc.update("UPDATE knowledge_entries SET source = 'manual' WHERE source IS NULL");
        jdbc.update("UPDATE knowledge_entries SET index_status = 'pending' WHERE index_status IS NULL");

        Integer unmigrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entries WHERE kb_id IS NULL", Integer.class);
        if (unmigrated == null || unmigrated == 0) {
            return;
        }

        long globalKbId = findOrCreateKb(GLOBAL_KB_NAME, "global", null,
                "CAP-04 存量全局经验条目（自动迁移）");
        int globalCount = jdbc.update(
                "UPDATE knowledge_entries SET kb_id = ? WHERE kb_id IS NULL AND (scope IS NULL OR scope = 'global')",
                globalKbId);

        int projectCount = 0;
        List<String> projectIds = jdbc.queryForList(
                "SELECT DISTINCT project_id FROM knowledge_entries WHERE kb_id IS NULL AND scope = 'project'"
                        + " AND project_id IS NOT NULL", String.class);
        for (String projectId : projectIds) {
            long kbId = findOrCreateKb(projectKbName(projectId), "project", projectId,
                    "CAP-04 存量项目经验条目（自动迁移）");
            projectCount += jdbc.update(
                    "UPDATE knowledge_entries SET kb_id = ? WHERE kb_id IS NULL AND scope = 'project' AND project_id = ?",
                    kbId, projectId);
        }
        log.info("知识库容器迁移完成：global {} 条、project {} 条（{} 个项目库）",
                globalCount, projectCount, projectIds.size());
    }

    private String projectKbName(String projectId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT name FROM projects WHERE id = ?", projectId);
            if (!rows.isEmpty() && rows.get(0).get("name") != null) {
                return rows.get(0).get("name") + "经验库";
            }
        } catch (Exception e) {
            log.debug("查项目名失败（用兜底库名）: {}", e.getMessage());
        }
        return "项目经验库(" + projectId + ")";
    }

    private long findOrCreateKb(String name, String scope, String projectId, String description) {
        Long existing = findKbId(name, scope, projectId);
        if (existing != null) {
            return existing;
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO knowledge_bases(name, description, scope, project_id, inject_mode, status,"
                        + " created_at, updated_at) VALUES (?,?,?,?, 'FULL', 'active', ?, ?)",
                name, description, scope, projectId, now, now);
        // 不回查 created_at（H2 TIMESTAMP 精度截断纳秒会判等失败），按去重同口径回查
        Long id = findKbId(name, scope, projectId);
        if (id == null) {
            throw new IllegalStateException("知识库创建后回查失败: " + name);
        }
        log.info("知识库容器迁移：建库 [{}] scope={} project={}", name, scope, projectId);
        return id;
    }

    private Long findKbId(String name, String scope, String projectId) {
        List<Map<String, Object>> rows = projectId == null
                ? jdbc.queryForList(
                        "SELECT id FROM knowledge_bases WHERE scope = ? AND name = ? AND project_id IS NULL",
                        scope, name)
                : jdbc.queryForList(
                        "SELECT id FROM knowledge_bases WHERE scope = ? AND name = ? AND project_id = ?",
                        scope, name, projectId);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    private boolean tableExists(String table) {
        try {
            // COUNT 恒返一行（queryForObject + SELECT 1 WHERE 1=0 空结果会抛 EmptyResultDataAccessException 误判不存在）
            jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE 1 = 0", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
