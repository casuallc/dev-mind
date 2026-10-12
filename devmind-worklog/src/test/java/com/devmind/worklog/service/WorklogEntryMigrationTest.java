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
import com.devmind.worklog.service.WorklogEntryFile.DayFile;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-74 M3 {@link WorklogEntryMigration}：H2 mem 建存量 worklog_entries 插行 + @TempDir 真仓。
 * 钉死：day 文件落盘（元数据 + legacy 幂等键 + 初始 preamble）、commit 时间回填 updated_at、
 * 二次运行零新 commit、同人两日分两个 commit、缺表 noop。
 */
class WorklogEntryMigrationTest {

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private WorklogRepoService repoService;
    private WorklogEntryMigration migration;
    private final Map<String, WorklogRepoEntity> repoRows = new HashMap<>();
    private final List<WorklogRepoMemberEntity> memberRows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:entrymig" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE worklog_entries (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " user_id VARCHAR(64), work_date DATE, title VARCHAR(256), content CLOB,"
                + " entry_type VARCHAR(32), minutes INT, source VARCHAR(16), repo_id BIGINT,"
                + " commit_sha VARCHAR(64), requirement_id VARCHAR(64), jira_issue_key VARCHAR(64),"
                + " created_at TIMESTAMP, updated_at TIMESTAMP)");

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
                settingsRepository, git, nullIdentityProvider(), nullGatewayProvider());
        migration = new WorklogEntryMigration(jdbc, repoService);
    }

    private void insert(String user, String date, String title, String content, String type,
                        Integer minutes, String source, Long repoId, String sha,
                        Instant created, Instant updated) {
        jdbc.update("INSERT INTO worklog_entries (user_id, work_date, title, content, entry_type,"
                        + " minutes, source, repo_id, commit_sha, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                user, java.sql.Date.valueOf(date), title, content, type, minutes, source, repoId, sha,
                created == null ? null : Timestamp.from(created),
                updated == null ? null : Timestamp.from(updated));
    }

    private DayFile dayFile(String user, LocalDate date) throws Exception {
        Path f = repoService.repoDir("personal-" + user).resolve(user + "/entries/" + date + ".md");
        return WorklogEntryFile.parse(Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void rowsMigratedToDayFileWithLegacyAndBackfilledTimestamps() throws Exception {
        Instant created = Instant.parse("2026-09-14T01:00:00Z");
        Instant updated = Instant.parse("2026-09-14T03:30:00Z");
        insert("alice", "2026-09-14", "联调日报生成", "接 prompt 渲染", "DEV", 60, "MANUAL",
                null, null, created, updated);
        insert("alice", "2026-09-14", "feat: 工作日志空间", null, "DEV", 0, "GIT",
                42L, "abc123", created, updated);

        migration.run(null);

        DayFile f = dayFile("alice", LocalDate.of(2026, 9, 14));
        assertEquals(2, f.entries().size());
        var e0 = f.entries().get(0);
        assertEquals("联调日报生成", e0.title());
        assertEquals("接 prompt 渲染", e0.body());
        assertEquals(60, e0.minutes());
        assertEquals("MANUAL", e0.source());
        assertEquals(created, e0.createdAt());
        assertEquals("1", e0.extraAttrs().get(WorklogEntryMigration.LEGACY_ATTR));
        var e1 = f.entries().get(1);
        assertEquals("GIT", e1.source());
        assertEquals(42L, e1.repoId());
        assertEquals("abc123", e1.commitSha());
        // 初始 preamble + commit 时间回填组内最新 updated_at
        String raw = Files.readString(
                repoService.repoDir("personal-alice").resolve("alice/entries/2026-09-14.md"),
                StandardCharsets.UTF_8);
        assertTrue(raw.startsWith("# 2026-09-14 工作条目\n"));
        assertEquals(updated, lastCommitInstant("personal-alice"));
    }

    @Test
    void secondRunIsIdempotentZeroNewCommits() throws Exception {
        insert("alice", "2026-09-14", "条目甲", null, "DEV", 30, "MANUAL",
                null, null, Instant.now(), Instant.now());
        migration.run(null);
        long first = commitCount("personal-alice");
        migration.run(null);
        assertEquals(first, commitCount("personal-alice"));
        // 追加一行新 DB 记录再跑：只补新行（legacy 判存）
        insert("alice", "2026-09-14", "条目乙", null, "MEETING", 15, "MANUAL",
                null, null, Instant.now(), Instant.now());
        migration.run(null);
        DayFile f = dayFile("alice", LocalDate.of(2026, 9, 14));
        assertEquals(2, f.entries().size());
        assertEquals("条目乙", f.entries().get(1).title());
    }

    @Test
    void sameUserTwoDaysProduceTwoCommits() throws Exception {
        insert("alice", "2026-09-14", "甲", null, "DEV", 30, "MANUAL",
                null, null, Instant.now(), Instant.now());
        insert("alice", "2026-09-15", "乙", null, "DEV", 30, "MANUAL",
                null, null, Instant.now(), Instant.now());
        migration.run(null);
        assertEquals(1, dayFile("alice", LocalDate.of(2026, 9, 14)).entries().size());
        assertEquals(1, dayFile("alice", LocalDate.of(2026, 9, 15)).entries().size());
        // 两日各一个迁移 commit（骨架 commit 不计）
        assertEquals(2, commitSubjects("personal-alice").stream()
                .filter(s -> s.startsWith("worklog: entries")).count());
    }

    @Test
    void unknownSourceFallsBackToManual() throws Exception {
        insert("alice", "2026-09-14", "旧源条目", null, "DEV", 30, "LEGACY",
                null, null, null, null);
        migration.run(null);
        DayFile f = dayFile("alice", LocalDate.of(2026, 9, 14));
        assertEquals("MANUAL", f.entries().get(0).source());
        assertTrue(f.entries().get(0).createdAt() != null, "created 双空回退当前时间");
    }

    @Test
    void freshDatabaseWithoutLegacyTableIsNoop() {
        jdbc.execute("DROP TABLE worklog_entries");
        migration.run(null); // 不炸、不建仓
        assertTrue(repoRows.isEmpty());
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

    private List<String> commitSubjects(String repoKey) throws Exception {
        Process p = new ProcessBuilder("git", "log", "--format=%s")
                .directory(repoService.repoDir(repoKey).toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git log 失败: " + out);
        }
        return out.lines().filter(s -> !s.isBlank()).toList();
    }

    private Instant lastCommitInstant(String repoKey) throws Exception {        Process p = new ProcessBuilder("git", "log", "-1", "--format=%cI")
                .directory(repoService.repoDir(repoKey).toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git log 失败: " + out);
        }
        // %cI 秒级精度：与回填 Instant 比对前截断到秒
        return Instant.parse(out.trim());
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

    private static ObjectProvider<com.devmind.common.integration.RepoGitGateway> nullGatewayProvider() {
        return new ObjectProvider<>() {
            public com.devmind.common.integration.RepoGitGateway getObject() { return null; }
            public com.devmind.common.integration.RepoGitGateway getObject(Object... args) { return null; }
            public com.devmind.common.integration.RepoGitGateway getIfAvailable() { return null; }
            public com.devmind.common.integration.RepoGitGateway getIfUnique() { return null; }
        };
    }
}
