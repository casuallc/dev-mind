package com.devmind.worklog.service;

import com.devmind.common.egress.EgressProxyRouter;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.integration.service.GitRemoteOps;
import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.model.WorklogRepoEntity;
import com.devmind.worklog.model.WorklogRepoMemberEntity;
import com.devmind.worklog.repo.WorklogRepoMemberRepository;
import com.devmind.worklog.repo.WorklogRepoRepository;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-74 C5 {@link WorklogReportMigration}：H2 mem 建存量表插行 + @TempDir 真仓。
 * 钉死：内容一致（字节级）、commit 时间回填 updated_at、二次运行零新 commit（幂等）、
 * 周报重组与 {@link ReportService#splitWeekly} 往返一致、settings 远端绑定搬到 repos 行、
 * 旧表缺席时不炸（新库）。
 */
class WorklogReportMigrationTest {

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private WorklogRepoService repoService;
    private WorklogReportMigration migration;
    private final Map<String, WorklogRepoEntity> repoRows = new HashMap<>();
    private final List<WorklogRepoMemberEntity> memberRows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mig" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE worklog_user_settings (user_id VARCHAR(64) PRIMARY KEY,"
                + " remote_url VARCHAR(512), remote_branch VARCHAR(64))");
        jdbc.execute("CREATE TABLE daily_reports (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " user_id VARCHAR(64), work_date DATE, content_md CLOB, session_id VARCHAR(16),"
                + " created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE weekly_reports (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " user_id VARCHAR(64), week_start DATE, summary_md CLOB, next_plan_md CLOB,"
                + " session_id VARCHAR(16), created_at TIMESTAMP, updated_at TIMESTAMP)");

        WorklogProperties props = new WorklogProperties();
        props.setRepoRoot(tmp.resolve("repos").toString());

        WorklogRepoRepository repoRepository = mock(WorklogRepoRepository.class);
        when(repoRepository.findByRepoKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(repoRows.get(inv.getArgument(0))));
        when(repoRepository.save(any(WorklogRepoEntity.class))).thenAnswer(inv -> {
            WorklogRepoEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.getAndIncrement());
            }
            repoRows.put(e.getRepoKey(), e);
            return e;
        });

        WorklogRepoMemberRepository memberRepository = mock(WorklogRepoMemberRepository.class);
        when(memberRepository.save(any(WorklogRepoMemberEntity.class))).thenAnswer(inv -> {
            WorklogRepoMemberEntity m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(ids.getAndIncrement());
            }
            memberRows.add(m);
            return m;
        });
        when(memberRepository.findByRepoIdAndUserId(anyLong(), anyString())).thenAnswer(inv ->
                memberRows.stream()
                        .filter(m -> m.getRepoId().equals(inv.getArgument(0))
                                && m.getUserId().equals(inv.getArgument(1)))
                        .findFirst());

        WorklogUserSettingsRepository settingsRepository = mock(WorklogUserSettingsRepository.class);
        when(settingsRepository.findByUserId(anyString())).thenReturn(Optional.empty());

        GitRemoteOps git = new GitRemoteOps(nullEgressProvider());
        repoService = new WorklogRepoService(props, repoRepository, memberRepository,
                settingsRepository, git, nullIdentityProvider());
        migration = new WorklogReportMigration(jdbc, repoService);
    }

    @Test
    void dailyMigratedWithCommitDateBackfillAndTrailers() {
        Instant created = Instant.parse("2026-10-10T08:00:00Z");
        Instant updated = Instant.parse("2026-10-10T10:15:30Z");
        jdbc.update("INSERT INTO daily_reports (user_id, work_date, content_md, session_id,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?)",
                "alice", java.sql.Date.valueOf("2026-10-10"), "# 日报A\n进展：CAP-74\n", "sess-1",
                Timestamp.from(created), Timestamp.from(updated));

        migration.run(null);

        var content = repoService.readReport("alice", WorklogRepoService.ReportKind.DAILY,
                LocalDate.of(2026, 10, 10));
        assertTrue(content.isPresent());
        assertEquals("# 日报A\n进展：CAP-74\n", content.get().contentMd());
        // commit 时间回填 updated_at（git %aI 秒级精度）；trailer 追溯 source/session
        assertEquals(updated, content.get().updatedAt());
        assertEquals("migration", content.get().source());
        assertEquals("sess-1", content.get().sessionId());
    }

    @Test
    void weeklyReassembleRoundTripsSplitWeekly() {
        Instant updated = Instant.parse("2026-10-11T09:00:00Z");
        jdbc.update("INSERT INTO weekly_reports (user_id, week_start, summary_md, next_plan_md,"
                        + " session_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                "alice", java.sql.Date.valueOf("2026-10-05"), "总结S", "计划P", null,
                Timestamp.from(updated), Timestamp.from(updated));
        // plan 为空：重组省略尾部标题
        jdbc.update("INSERT INTO weekly_reports (user_id, week_start, summary_md, next_plan_md,"
                        + " session_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                "bob", java.sql.Date.valueOf("2026-10-05"), "只有总结", null, null,
                Timestamp.from(updated), Timestamp.from(updated));

        migration.run(null);

        var aliceWeekly = repoService.readReport("alice", WorklogRepoService.ReportKind.WEEKLY,
                LocalDate.of(2026, 10, 5)).orElseThrow();
        assertEquals(ReportService.assembleWeekly("总结S", "计划P"), aliceWeekly.contentMd());
        String[] parts = ReportService.splitWeekly(aliceWeekly.contentMd());
        assertEquals("总结S", parts[0]);
        assertEquals("计划P", parts[1]);

        var bobWeekly = repoService.readReport("bob", WorklogRepoService.ReportKind.WEEKLY,
                LocalDate.of(2026, 10, 5)).orElseThrow();
        String[] bobParts = ReportService.splitWeekly(bobWeekly.contentMd());
        assertEquals("只有总结", bobParts[0]);
        assertEquals("", bobParts[1]);
    }

    @Test
    void secondRunIsIdempotentZeroNewCommits() throws Exception {
        jdbc.update("INSERT INTO daily_reports (user_id, work_date, content_md, session_id,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?)",
                "alice", java.sql.Date.valueOf("2026-10-10"), "内容\n", null,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        migration.run(null);
        long first = commitCount("personal-alice");
        migration.run(null);
        assertEquals(first, commitCount("personal-alice"));
    }

    @Test
    void remoteBindingMigratedToRepoRowOnlyWhenUnbound() {
        jdbc.update("INSERT INTO worklog_user_settings (user_id, remote_url, remote_branch)"
                + " VALUES (?,?,?)", "alice", "file:///tmp/backup.git", "backup");
        jdbc.update("INSERT INTO worklog_user_settings (user_id, remote_url, remote_branch)"
                + " VALUES (?,?,?)", "bob", "file:///tmp/bob.git", null);

        // bob 的仓行已绑定（settings 旧值不得覆盖新口径）
        var bobRepo = repoService.ensurePersonalRepo("bob");
        bobRepo.setRemoteUrl("https://git.example.com/bob.git");
        repoService.saveRepo(bobRepo);

        migration.run(null);

        WorklogRepoEntity aliceRepo = repoRows.get("personal-alice");
        assertEquals("file:///tmp/backup.git", aliceRepo.getRemoteUrl());
        assertEquals("backup", aliceRepo.getRemoteBranch());
        WorklogRepoEntity bobAfter = repoRows.get("personal-bob");
        assertEquals("https://git.example.com/bob.git", bobAfter.getRemoteUrl());
        assertEquals("main", bobAfter.getRemoteBranch());
    }

    @Test
    void freshDatabaseWithoutLegacyTablesIsNoop() {
        jdbc.execute("DROP TABLE daily_reports");
        jdbc.execute("DROP TABLE weekly_reports");
        migration.run(null); // 不炸、不建仓
        assertTrue(repoRows.isEmpty());
        assertNull(repoRows.get("personal-alice"));
    }

    // ---------------- 工装 ----------------

    private long commitCount(String repoKey) throws Exception {
        Process p = new ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .directory(repoService.repoDir(repoKey).toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git rev-list 失败: " + out);
        }
        return Long.parseLong(out.trim());
    }

    private static ObjectProvider<EgressProxyRouter> nullEgressProvider() {
        return new ObjectProvider<>() {
            public EgressProxyRouter getObject() { return null; }
            public EgressProxyRouter getObject(Object... args) { return null; }
            public EgressProxyRouter getIfAvailable() { return null; }
            public EgressProxyRouter getIfUnique() { return null; }
        };
    }

    private static ObjectProvider<GitIdentityProvider> nullIdentityProvider() {
        return new ObjectProvider<>() {
            public GitIdentityProvider getObject() { return null; }
            public GitIdentityProvider getObject(Object... args) { return null; }
            public GitIdentityProvider getIfAvailable() { return null; }
            public GitIdentityProvider getIfUnique() { return null; }
        };
    }
}
