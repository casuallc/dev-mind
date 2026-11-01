package com.devmind.test.service;

import com.devmind.test.model.TestCaseEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.spec.CaseSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-104 存量兜底：CAP-10 旧用例/套件没有资产字段，启动时补齐（幂等，只记日志不阻断启动）。
 * caseKey = LEGACY-&lt;id&gt;，type 按 kind 映射，版本 1 且已审核；套件无评审状态视为已审核，
 * 避免升级后已有的回归链被评审门禁挡停。
 */
@Component
public class TestDataBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestDataBackfill.class);

    private final JdbcTemplate jdbc;
    private final TestCaseRepository caseRepo;

    public TestDataBackfill(JdbcTemplate jdbc, TestCaseRepository caseRepo) {
        this.jdbc = jdbc;
        this.caseRepo = caseRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("测试资产存量迁移失败（不阻断启动，可重启重试）: {}", e.getMessage());
        }
    }

    private void migrate() {
        update("UPDATE test_cases SET case_key = CONCAT('LEGACY-', id) WHERE case_key IS NULL");
        update("UPDATE test_cases SET type = CASE "
                + "WHEN LOWER(kind) = 'health' THEN 'COMMAND' "
                + "WHEN LOWER(kind) = 'manual' THEN 'MANUAL' ELSE 'HTTP' END WHERE type IS NULL");
        update("UPDATE test_cases SET case_version = 1 WHERE case_version IS NULL");
        update("UPDATE test_cases SET review_status = 'APPROVED' WHERE review_status IS NULL");
        update("UPDATE test_suites SET review_status = 'APPROVED' WHERE review_status IS NULL");
        backfillSignatures();
    }

    private void update(String sql) {
        try {
            int n = jdbc.update(sql);
            if (n > 0) {
                log.info("CAP-104 存量迁移：{} 行（{}）", n, sql.substring(0, Math.min(80, sql.length())));
            }
        } catch (Exception e) {
            // 列不存在（老库升级中途）不算错，下次启动 ddl 建好后自然补上
            log.debug("跳过存量迁移: {}", e.getMessage());
        }
    }

    private void backfillSignatures() {
        try {
            List<TestCaseEntity> missing = caseRepo.findBySignatureNull();
            // 只补签名：caseKey/type/version 已由 SQL 补过
            if (missing.isEmpty()) {
                return;
            }
            for (TestCaseEntity c : missing) {
                c.setSignature(CaseSignatures.ofLegacy(c.getKind(), c.getMethod(), c.getPath(), c.getExpectedJson()));
                caseRepo.save(c);
            }
            log.info("CAP-104 存量用例签名回填: {} 条", missing.size());
        } catch (Exception e) {
            log.debug("签名回填跳过: {}", e.getMessage());
        }
    }
}
