package com.devmind.worklog.service;

import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import com.devmind.worklog.repo.WorklogEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CAP-89 FR-05 仓→DB 回迁单测：临时目录预置仓文件，repository 走 Mockito mock
 * （真库幂等由 E2E 二次启动覆盖）。钉住：判存跳过、周报拆两列、条目 marker uuid 作主键、
 * git 条目（repoId,sha）判重、personal-* 仓先扫先赢、无仓目录零动作。
 */
class WorklogRepoToDbMigrationTest {

    @TempDir
    Path root;

    private DailyReportRepository dailyRepo;
    private WeeklyReportRepository weeklyRepo;
    private WorklogEntryRepository entryRepo;
    private WorklogRepoToDbMigration migration;

    @BeforeEach
    void setUp() {
        dailyRepo = mock(DailyReportRepository.class);
        weeklyRepo = mock(WeeklyReportRepository.class);
        entryRepo = mock(WorklogEntryRepository.class);
        WorklogProperties props = new WorklogProperties();
        props.setRepoRoot(root.toString());
        migration = new WorklogRepoToDbMigration(props, dailyRepo, weeklyRepo, entryRepo);
    }

    private Path write(String rel, String content) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private void run() {
        migration.run(null);
    }

    @Test
    void 日报周报迁入并判存跳过() throws IOException {
        write("personal-alice/alice/daily/2026-10-20.md", "# 日报内容");
        write("personal-alice/alice/weekly/2026-W43.md",
                ReportService.SUMMARY_HEADING + "\n- 完成 A\n\n" + ReportService.PLAN_HEADING + "\n- 继续 B\n");
        when(dailyRepo.existsByUserIdAndWorkDate("alice", LocalDate.of(2026, 10, 20))).thenReturn(false);
        when(weeklyRepo.existsByUserIdAndWeekStart("alice", LocalDate.of(2026, 10, 19))).thenReturn(false);

        run();

        ArgumentCaptor<DailyReportEntity> dc = ArgumentCaptor.forClass(DailyReportEntity.class);
        verify(dailyRepo).save(dc.capture());
        assertEquals("alice", dc.getValue().getUserId());
        assertEquals(LocalDate.of(2026, 10, 20), dc.getValue().getWorkDate());
        assertEquals("# 日报内容", dc.getValue().getContentMd());
        assertTrue(dc.getValue().getCreatedAt() != null && dc.getValue().getUpdatedAt() != null,
                "时间回退文件 mtime，不留 null");

        ArgumentCaptor<WeeklyReportEntity> wc = ArgumentCaptor.forClass(WeeklyReportEntity.class);
        verify(weeklyRepo).save(wc.capture());
        assertEquals(LocalDate.of(2026, 10, 19), wc.getValue().getWeekStart());
        assertEquals("- 完成 A", wc.getValue().getSummaryMd());
        assertEquals("- 继续 B", wc.getValue().getNextPlanMd());
    }

    @Test
    void 已有行跳过不覆盖() throws IOException {
        write("personal-alice/alice/daily/2026-10-20.md", "仓内旧内容");
        when(dailyRepo.existsByUserIdAndWorkDate("alice", LocalDate.of(2026, 10, 20))).thenReturn(true);
        run();
        verify(dailyRepo, times(0)).save(any());
    }

    @Test
    void 条目按marker_uuid作主键迁入() throws IOException {
        String id = "0123456789abcdef0123456789abcdef";
        write("personal-bob/bob/entries/2026-10-21.md",
                "# 2026-10-21 工作条目\n\n"
                        + "<!-- devmind:entry id=" + id + " type=MEETING hours=1.5 source=MANUAL"
                        + " repo=- sha=- req=REQ-1 jira=- created=2026-10-21T02:30:00Z -->\n"
                        + "## 需求评审\n讨论了排期\n");
        when(entryRepo.existsById(id)).thenReturn(false);

        run();

        ArgumentCaptor<WorklogEntryEntity> cap = ArgumentCaptor.forClass(WorklogEntryEntity.class);
        verify(entryRepo).save(cap.capture());
        WorklogEntryEntity e = cap.getValue();
        assertEquals(id, e.getId(), "marker uuid 直接沿用为 DB 主键");
        assertEquals("bob", e.getUserId());
        assertEquals(LocalDate.of(2026, 10, 21), e.getWorkDate());
        assertEquals("需求评审", e.getTitle());
        assertEquals("讨论了排期", e.getContent());
        assertEquals("MEETING", e.getEntryType());
        assertEquals(90, e.getMinutes());
        assertEquals("REQ-1", e.getRequirementId());
        assertEquals(java.time.Instant.parse("2026-10-21T02:30:00Z"), e.getCreatedAt());
    }

    @Test
    void 条目按uuid判存跳过且git条目按sha判重() throws IOException {
        String dup = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String git = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        write("personal-bob/bob/entries/2026-10-21.md",
                "<!-- devmind:entry id=" + dup + " type=DEV hours=1 source=MANUAL repo=- sha=- req=- jira=- created=- -->\n"
                        + "## 已有条目\n\n"
                        + "<!-- devmind:entry id=" + git + " type=DEV hours=0.5 source=GIT repo=7 sha=abc123 req=- jira=- created=- -->\n"
                        + "## 提交条目\n");
        when(entryRepo.existsById(dup)).thenReturn(true);   // uuid 撞 → 跳过
        when(entryRepo.existsById(git)).thenReturn(false);
        when(entryRepo.existsByUserIdAndRepoIdAndCommitSha("bob", 7L, "abc123")).thenReturn(true); // sha 撞 → 跳过

        run();
        verify(entryRepo, times(0)).save(any());
    }

    @Test
    void 多仓冲突个人仓先扫先赢() throws IOException {
        write("team-x/alice/daily/2026-10-20.md", "团队仓版本");
        write("personal-alice/alice/daily/2026-10-20.md", "个人仓版本");
        // 第一次判存 false（迁入个人仓版），第二次判存 true（团队仓版跳过）——模拟唯一约束效果
        when(dailyRepo.existsByUserIdAndWorkDate("alice", LocalDate.of(2026, 10, 20)))
                .thenReturn(false, true);

        run();

        ArgumentCaptor<DailyReportEntity> cap = ArgumentCaptor.forClass(DailyReportEntity.class);
        verify(dailyRepo, times(1)).save(cap.capture());
        assertEquals("个人仓版本", cap.getValue().getContentMd(), "personal-* 排序在前，先扫先赢");
    }

    @Test
    void 仓根不存在零动作() {
        WorklogProperties props = new WorklogProperties();
        props.setRepoRoot(root.resolve("not-exist").toString());
        new WorklogRepoToDbMigration(props, dailyRepo, weeklyRepo, entryRepo).run(null);
        verifyNoInteractions(dailyRepo, weeklyRepo, entryRepo);
    }

    @Test
    void 非法文件名与脏目录跳过不阻断() throws IOException {
        write("personal-alice/alice/daily/notes.txt", "非契约文件");
        write("personal-alice/alice/daily/2026-13-99.md", "非法日期"); // 解析失败跳过
        write("personal-alice/alice/daily/2026-10-22.md", "正常日报");
        write("personal-alice/.git", "伪装目录被白名单放行但无内容"); // .git 命中白名单但无 daily/weekly/entries
        run();
        ArgumentCaptor<DailyReportEntity> cap = ArgumentCaptor.forClass(DailyReportEntity.class);
        verify(dailyRepo, times(1)).save(cap.capture());
        assertEquals(LocalDate.of(2026, 10, 22), cap.getValue().getWorkDate());
    }
}
