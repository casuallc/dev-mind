package com.devmind.decisionlab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * CAP-56 存量兜底迁移（照 {@code KnowledgeBaseMigration} 的口径：幂等、只记日志不阻断启动）。
 *
 * <p><b>为什么需要它</b>：Boolean 列按项目红线<b>禁 {@code @ColumnDefault}</b>
 * （MySQL bit 列 default 'false' 建列失败），所以存量行的 {@code frozen} 是 NULL。
 * 实体 getter 兜底读得出 false，但"NULL 不等于 false"会让任何按列筛选的 SQL 漏掉这些行
 * （比如将来"列出未冻结的集"），于是显式补一次。</p>
 *
 * <p>表已存在但列刚加出来的第一次启动，这里补的就是全部存量行；之后每次启动都是 0 行，
 * 一条 UPDATE 的成本。任何异常只记日志——迁移失败不该让平台起不来。</p>
 */
@Component
public class DecisionLabMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DecisionLabMigration.class);

    private final JdbcTemplate jdbc;

    public DecisionLabMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("决策实验室存量迁移失败（不阻断启动，可重启重试）: {}", e.getMessage());
        }
    }

    private void migrate() {
        backfill("decision_datasets", "frozen");
        backfill("decision_checkpoints", "verified");
    }

    /** 把 NULL 补成 false；表/列不存在（模块刚上线、ddl 还没跑到）时静默跳过 */
    private void backfill(String table, String column) {
        if (!tableExists(table)) {
            return;
        }
        try {
            int fixed = jdbc.update("UPDATE " + table + " SET " + column + " = false WHERE " + column + " IS NULL");
            if (fixed > 0) {
                log.info("决策实验室存量兜底：{} 行 {}.{} 补 false", fixed, table, column);
            }
        } catch (Exception e) {
            // 列不存在（老库升级中途）不能算错：下次启动 ddl 建好列后自然会补上
            log.debug("跳过 {}.{} 兜底: {}", table, column, e.getMessage());
        }
    }

    private boolean tableExists(String table) {
        try {
            // COUNT 恒返一行：queryForObject + "SELECT 1 WHERE 1=0" 的空结果会抛异常，误判成表不存在
            jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE 1 = 0", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
