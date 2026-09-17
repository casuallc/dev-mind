package com.devmind.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CAP-44 FR-02 存量迁移：经验条目 → 知识库容器归属（真 H2 内存库，DDL 只建迁移触及的列）。
 * 覆盖：全局/项目条目回填、新列默认值兜底、幂等（二次执行不重复建库不回填）。
 */
class KnowledgeBaseMigrationTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:kbmig" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE knowledge_bases("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(128) NOT NULL,"
                + " description VARCHAR(512), scope VARCHAR(16) NOT NULL, project_id VARCHAR(64),"
                + " inject_mode VARCHAR(8) NOT NULL, embedding_model VARCHAR(64), status VARCHAR(16) NOT NULL,"
                + " created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE knowledge_entries("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, kb_id BIGINT, scope VARCHAR(16),"
                + " project_id VARCHAR(64), name VARCHAR(200), source VARCHAR(16), index_status VARCHAR(16),"
                + " created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE projects(id VARCHAR(64) PRIMARY KEY, name VARCHAR(128))");
    }

    private void insertEntry(String scope, String projectId, String name) {
        jdbc.update("INSERT INTO knowledge_entries(scope, project_id, name, created_at) VALUES (?,?,?,?)",
                scope, projectId, name, Timestamp.from(Instant.now()));
    }

    @Test
    void migratesLegacyEntriesIntoContainers() {
        jdbc.update("INSERT INTO projects(id, name) VALUES ('p1', '商城')");
        insertEntry("global", null, "提交规范");
        insertEntry("project", "p1", "架构说明");
        insertEntry("project", "ghost", "孤儿条目");

        new KnowledgeBaseMigration(jdbc).run(null);

        Integer globalKb = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_bases WHERE scope='global' AND inject_mode='FULL'", Integer.class);
        assertEquals(1, globalKb, "应建唯一全局经验库");
        Integer projectKbs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_bases WHERE scope='project'", Integer.class);
        assertEquals(2, projectKbs, "两个 project_id 各建一个项目库");
        String kbName = jdbc.queryForObject(
                "SELECT name FROM knowledge_bases WHERE project_id='p1'", String.class);
        assertEquals("商城经验库", kbName, "项目库名取项目名");
        Integer backfilled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entries WHERE kb_id IS NOT NULL", Integer.class);
        assertEquals(3, backfilled, "全部条目回填 kb_id");
        Integer defaults = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entries WHERE source='manual' AND index_status='pending'",
                Integer.class);
        assertEquals(3, defaults, "新列默认值兜底");
    }

    @Test
    void idempotentOnSecondRun() {
        insertEntry("global", null, "A");
        KnowledgeBaseMigration migration = new KnowledgeBaseMigration(jdbc);
        migration.run(null);
        Long kbId = jdbc.queryForObject("SELECT kb_id FROM knowledge_entries LIMIT 1", Long.class);

        migration.run(null);

        Integer kbCount = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_bases", Integer.class);
        assertEquals(1, kbCount, "二次执行不重复建库");
        Long kbIdAfter = jdbc.queryForObject("SELECT kb_id FROM knowledge_entries LIMIT 1", Long.class);
        assertEquals(kbId, kbIdAfter, "二次执行不改已回填归属");
    }

    @Test
    void preExistingGlobalKbIsReused() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO knowledge_bases(name, scope, inject_mode, status, created_at, updated_at)"
                + " VALUES ('全局经验库','global','FULL','active',?,?)", now, now);
        insertEntry("global", null, "A");

        new KnowledgeBaseMigration(jdbc).run(null);

        Integer kbCount = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_bases", Integer.class);
        assertEquals(1, kbCount, "已有全局经验库应复用不另建");
    }
}
