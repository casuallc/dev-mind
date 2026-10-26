package com.devmind.worklog.service;

import com.devmind.worklog.config.WorklogProperties;
import com.devmind.worklog.model.DailyReportEntity;
import com.devmind.worklog.model.WeeklyReportEntity;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.repo.DailyReportRepository;
import com.devmind.worklog.repo.WeeklyReportRepository;
import com.devmind.worklog.repo.WorklogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CAP-89 FR-05：存量服务端 git 仓 → DB 一次性回迁（启动期幂等执行）。
 *
 * <p>扫描 {@code repoRoot/<repoKey>/<subdir>/{daily,weekly,entries}}：
 * <ul>
 *   <li>subdir = 归属用户（白名单 [A-Za-z0-9._-]；目录契约时代 subdir 即 username）；</li>
 *   <li>日报/周报按（userId, 日期）判存，已有行跳过（DB 已是事实源，仓文件不覆盖）；</li>
 *   <li>条目按 marker 里的 uuid 作 DB 主键判存（文件时代主键形态即 String uuid，直接沿用）；
 *       git 导入条目另按（userId, repoId, commitSha）判重；</li>
 *   <li>多仓同名冲突（团队仓年代可能同一用户散在多仓）：personal-* 仓先扫先赢，后到跳过；</li>
 *   <li>时间戳：条目用 marker created 属性，报告与缺失者回退文件 mtime。</li>
 * </ul>
 * 全程逐文件容错（单文件坏不阻断其余）；repoRoot 不存在 = 全新部署，直接跳过。
 * 跑完后仓目录成为死数据（保留不删，用户确认无误后可手工清理）。</p>
 */
@Component
public class WorklogRepoToDbMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorklogRepoToDbMigration.class);

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern DAILY_NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.md");
    private static final Pattern WEEKLY_NAME = Pattern.compile("(\\d{4})-W(\\d{2})\\.md");
    private static final java.util.Set<String> ENTRY_TYPES = java.util.Set.of(
            WorklogEntryEntity.TYPE_DEV, WorklogEntryEntity.TYPE_SUPPORT, WorklogEntryEntity.TYPE_MEETING,
            WorklogEntryEntity.TYPE_RESEARCH, WorklogEntryEntity.TYPE_OTHER);

    private final WorklogProperties properties;
    private final DailyReportRepository dailyRepo;
    private final WeeklyReportRepository weeklyRepo;
    private final WorklogEntryRepository entryRepo;

    public WorklogRepoToDbMigration(WorklogProperties properties,
                                    DailyReportRepository dailyRepo,
                                    WeeklyReportRepository weeklyRepo,
                                    WorklogEntryRepository entryRepo) {
        this.properties = properties;
        this.dailyRepo = dailyRepo;
        this.weeklyRepo = weeklyRepo;
        this.entryRepo = entryRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path root = Path.of(properties.getRepoRoot()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return; // 无存量仓（全新部署或已清理），跳过
        }
        int[] daily = {0, 0};   // [迁移, 跳过]
        int[] weekly = {0, 0};
        int[] entry = {0, 0};
        // personal-* 先扫：多仓同名冲突时个人仓内容先赢
        List<Path> repos;
        try (Stream<Path> s = Files.list(root)) {
            repos = s.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> !p.getFileName().toString().startsWith("personal-"))
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("工作日志仓根目录扫描失败: " + root, e);
        }
        for (Path repo : repos) {
            List<Path> subdirs;
            try (Stream<Path> s = Files.list(repo)) {
                subdirs = s.filter(Files::isDirectory)
                        .filter(p -> SAFE_NAME.matcher(p.getFileName().toString()).matches())
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
            } catch (IOException e) {
                log.warn("工作日志仓迁移：子目录扫描失败，跳过仓 {}: {}", repo.getFileName(), e.getMessage());
                continue;
            }
            for (Path subdir : subdirs) {
                String username = subdir.getFileName().toString();
                migrateReports(username, subdir.resolve("daily"), true, daily);
                migrateReports(username, subdir.resolve("weekly"), false, weekly);
                int[] r = migrateEntries(username, subdir.resolve("entries"));
                entry[0] += r[0];
                entry[1] += r[1];
            }
        }
        log.info("工作日志仓→DB 回迁完成: 日报 +{}/跳{} 周报 +{}/跳{} 条目 +{}/跳{}",
                daily[0], daily[1], weekly[0], weekly[1], entry[0], entry[1]);
    }

    // ---------------- 报告 ----------------

    private void migrateReports(String username, Path dir, boolean isDaily, int[] counters) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.warn("工作日志仓迁移：目录扫描失败，跳过 {}: {}", dir, e.getMessage());
            return;
        }
        for (Path f : files) {
            String name = f.getFileName().toString();
            try {
                if (isDaily) {
                    Matcher m = DAILY_NAME.matcher(name);
                    if (!m.matches()) {
                        continue;
                    }
                    migrateDaily(username, LocalDate.parse(m.group(1)), f, counters);
                } else {
                    Matcher m = WEEKLY_NAME.matcher(name);
                    if (!m.matches()) {
                        continue;
                    }
                    migrateWeekly(username, WorklogWeeks.weekStartOf(
                            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), f, counters);
                }
            } catch (Exception e) {
                log.warn("工作日志仓迁移：文件失败，跳过 {}: {}", f, e.getMessage());
            }
        }
    }

    private void migrateDaily(String username, LocalDate date, Path file, int[] counters) throws IOException {
        if (dailyRepo.existsByUserIdAndWorkDate(username, date)) {
            counters[1]++;
            return;
        }
        DailyReportEntity e = new DailyReportEntity();
        e.setUserId(username);
        e.setWorkDate(date);
        e.setContentMd(Files.readString(file, StandardCharsets.UTF_8));
        Instant t = mtime(file);
        e.setCreatedAt(t);
        e.setUpdatedAt(t);
        dailyRepo.save(e);
        counters[0]++;
    }

    private void migrateWeekly(String username, LocalDate weekStart, Path file, int[] counters) throws IOException {
        if (weeklyRepo.existsByUserIdAndWeekStart(username, weekStart)) {
            counters[1]++;
            return;
        }
        String[] parts = ReportService.splitWeekly(Files.readString(file, StandardCharsets.UTF_8));
        WeeklyReportEntity e = new WeeklyReportEntity();
        e.setUserId(username);
        e.setWeekStart(weekStart);
        e.setSummaryMd(parts[0]);
        e.setNextPlanMd(parts[1]);
        Instant t = mtime(file);
        e.setCreatedAt(t);
        e.setUpdatedAt(t);
        weeklyRepo.save(e);
        counters[0]++;
    }

    // ---------------- 条目 ----------------

    /**
     * 条目回迁：按 marker uuid 判存（uuid 缺/撞 → 跳过不补发——重跑幂等的命根子；
     * git 条目另按（userId, repoId, sha）判重兜底）。
     *
     * @return [迁移数, 跳过数]
     */
    private int[] migrateEntries(String username, Path dir) {
        int[] counters = {0, 0};
        if (!Files.isDirectory(dir)) {
            return counters;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.warn("工作日志仓迁移：条目目录扫描失败，跳过 {}: {}", dir, e.getMessage());
            return counters;
        }
        for (Path f : files) {
            Matcher m = DAILY_NAME.matcher(f.getFileName().toString());
            if (!m.matches()) {
                continue;
            }
            LocalDate workDate;
            try {
                workDate = LocalDate.parse(m.group(1));
            } catch (Exception e) {
                continue;
            }
            try {
                WorklogEntryFile.DayFile day = WorklogEntryFile.parse(
                        Files.readString(f, StandardCharsets.UTF_8));
                Instant fallback = mtime(f);
                for (WorklogEntryFile.ParsedEntry p : day.entries()) {
                    counters[p.id() != null && migrateEntry(username, workDate, p, fallback) ? 0 : 1]++;
                }
            } catch (Exception e) {
                log.warn("工作日志仓迁移：条目文件失败，跳过 {}: {}", f, e.getMessage());
            }
        }
        return counters;
    }

    /** @return true=迁入 false=跳过 */
    private boolean migrateEntry(String username, LocalDate workDate,
                                 WorklogEntryFile.ParsedEntry p, Instant fallback) {
        if (entryRepo.existsById(p.id())) {
            return false;
        }
        if (p.repoId() != null && p.commitSha() != null
                && entryRepo.existsByUserIdAndRepoIdAndCommitSha(username, p.repoId(), p.commitSha())) {
            return false;
        }
        WorklogEntryEntity e = new WorklogEntryEntity();
        e.setId(p.id());
        e.setUserId(username);
        e.setWorkDate(workDate);
        String title = p.title() == null ? "" : p.title();
        e.setTitle(title.length() > 256 ? title.substring(0, 256) : title);
        e.setContent(p.body());
        e.setEntryType(ENTRY_TYPES.contains(p.entryType()) ? p.entryType() : WorklogEntryEntity.TYPE_DEV);
        e.setMinutes(p.minutes());
        e.setSource(p.source() == null || p.source().isBlank()
                ? WorklogEntryEntity.SOURCE_MANUAL : p.source());
        e.setRepoId(p.repoId());
        e.setCommitSha(p.commitSha());
        e.setRequirementId(p.requirementId());
        e.setJiraIssueKey(p.jiraIssueKey());
        Instant created = p.createdAt() == null ? fallback : p.createdAt().truncatedTo(ChronoUnit.SECONDS);
        e.setCreatedAt(created);
        e.setUpdatedAt(created);
        entryRepo.save(e);
        return true;
    }

    private static Instant mtime(Path f) {
        try {
            return Files.getLastModifiedTime(f).toInstant().truncatedTo(ChronoUnit.SECONDS);
        } catch (IOException e) {
            return Instant.now().truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
