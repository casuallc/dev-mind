package com.devmind.worklog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * CAP-74 C5 存量迁移：daily_reports/weekly_reports（CAP-41 DB 镜像表，实体已删）→ 服务端 git 仓；
 * 顺带把 worklog_user_settings.remote_url/remote_branch 搬到 worklog_repos 个人仓行（D5 路由）。
 *
 * <p>幂等：目标文件字节级一致即跳过（零新 commit）；commit 时间回填 updated_at（git 历史即审计）。
 * 逐行 try/catch 失败 warn 续跑，任何异常不阻断启动；旧表不删（ddl-auto 不管删表，手工 DROP 口径
 * 见 CAP-74 文档）。</p>
 */
@Component
public class WorklogReportMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorklogReportMigration.class);

    private final JdbcTemplate jdbc;
    private final WorklogRepoService repoService;

    public WorklogReportMigration(JdbcTemplate jdbc, WorklogRepoService repoService) {
        this.jdbc = jdbc;
        this.repoService = repoService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("CAP-74 存量工作日志迁移失败（不阻断启动，可重启重试）: {}", e.getMessage());
        }
    }

    private void migrate() {
        migrateRemoteBindings();
        boolean hasDaily = tableExists("daily_reports");
        boolean hasWeekly = tableExists("weekly_reports");
        if (!hasDaily && !hasWeekly) {
            return;
        }
        if (hasDaily) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, user_id, work_date, content_md, session_id, created_at, updated_at"
                            + " FROM daily_reports");
            int migrated = 0;
            int skipped = 0;
            int failed = 0;
            for (Map<String, Object> row : rows) {
                try {
                    String user = (String) row.get("user_id");
                    LocalDate date = toLocalDate(row.get("work_date"));
                    if (user == null || user.isBlank() || date == null) {
                        skipped++;
                        continue;
                    }
                    String content = row.get("content_md") == null ? "" : (String) row.get("content_md");
                    Boolean r = migrateOne(user, WorklogRepoService.ReportKind.DAILY, date, content,
                            (String) row.get("session_id"), commitDate(row));
                    if (r == null) {
                        skipped++;
                    } else {
                        migrated++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("CAP-74 日报迁移单行失败（跳过续跑）: row={} err={}", row.get("id"), e.getMessage());
                }
            }
            log.info("CAP-74 日报迁移完成: 迁移 {} 条，跳过 {} 条，失败 {} 条", migrated, skipped, failed);
        }
        if (hasWeekly) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, user_id, week_start, summary_md, next_plan_md, session_id,"
                            + " created_at, updated_at FROM weekly_reports");
            int migrated = 0;
            int skipped = 0;
            int failed = 0;
            for (Map<String, Object> row : rows) {
                try {
                    String user = (String) row.get("user_id");
                    LocalDate weekStart = toLocalDate(row.get("week_start"));
                    if (user == null || user.isBlank() || weekStart == null) {
                        skipped++;
                        continue;
                    }
                    // 重组回单文件（与 splitWeekly 往返一致由测试钉死）
                    String content = ReportService.assembleWeekly(
                            row.get("summary_md") == null ? "" : (String) row.get("summary_md"),
                            row.get("next_plan_md") == null ? "" : (String) row.get("next_plan_md"));
                    Boolean r = migrateOne(user, WorklogRepoService.ReportKind.WEEKLY, weekStart, content,
                            (String) row.get("session_id"), commitDate(row));
                    if (r == null) {
                        skipped++;
                    } else {
                        migrated++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("CAP-74 周报迁移单行失败（跳过续跑）: row={} err={}", row.get("id"), e.getMessage());
                }
            }
            log.info("CAP-74 周报迁移完成: 迁移 {} 条，跳过 {} 条，失败 {} 条", migrated, skipped, failed);
        }
    }

    /**
     * 迁移单行到个人仓：目标文件字节级一致 → null（跳过）；否则写入并 commit
     * （source=migration、sessionId 追溯、commitDate 回填）→ true。
     */
    private Boolean migrateOne(String user, WorklogRepoService.ReportKind kind, LocalDate date,
                               String content, String sessionId, Instant commitDate) {
        var repo = repoService.ensurePersonalRepo(user);
        Path file = repoService.repoDir(repo.getRepoKey())
                .resolve(WorklogRepoService.reportRelPath(user, kind, date));
        try {
            if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
                return null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("目标文件读取失败: " + file, e);
        }
        repoService.writeReportTo(repo, user, kind, date, content,
                WorklogRepoService.CommitSource.MIGRATION,
                sessionId == null || sessionId.isBlank() ? null : sessionId, commitDate);
        return true;
    }

    /** settings.remote_url/remote_branch → 个人仓行（仓行已绑定才跳过；settings 表恒在无需判存）。 */
    private void migrateRemoteBindings() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, remote_url, remote_branch FROM worklog_user_settings"
                        + " WHERE remote_url IS NOT NULL");
        int migrated = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            try {
                String user = (String) row.get("user_id");
                String url = (String) row.get("remote_url");
                if (user == null || user.isBlank() || url == null || url.isBlank()) {
                    skipped++;
                    continue;
                }
                var repo = repoService.ensurePersonalRepo(user);
                if (repo.getRemoteUrl() != null && !repo.getRemoteUrl().isBlank()) {
                    skipped++;
                    continue;
                }
                repo.setRemoteUrl(url);
                String branch = (String) row.get("remote_branch");
                if (branch != null && !branch.isBlank()) {
                    repo.setRemoteBranch(branch);
                }
                repoService.saveRepo(repo);
                migrated++;
            } catch (Exception e) {
                skipped++;
                log.warn("CAP-74 远端绑定迁移单行失败（跳过续跑）: user={} err={}",
                        row.get("user_id"), e.getMessage());
            }
        }
        if (!rows.isEmpty()) {
            log.info("CAP-74 远端绑定迁移完成: 迁移 {} 条，跳过 {} 条", migrated, skipped);
        }
    }

    /** commit 时间回填口径：updated_at 优先，空回退 created_at，皆空 null（= 现在）。 */
    private static Instant commitDate(Map<String, Object> row) {
        Instant updated = toInstant(row.get("updated_at"));
        return updated != null ? updated : toInstant(row.get("created_at"));
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(v.toString().substring(0, 10));
    }

    private static Instant toInstant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant();
        }
        return Instant.parse(v.toString());
    }

    /** COUNT(*) 判存（SELECT 1 WHERE 1=0 空结果会抛异常误判——红线）。 */
    private boolean tableExists(String table) {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
