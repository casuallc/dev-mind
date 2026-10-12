package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitRepoCatalog;
import com.devmind.worklog.dto.EntryPage;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.dto.EntryView;
import com.devmind.worklog.dto.GitImportRequest;
import com.devmind.worklog.model.WorklogRepoEntity;
import com.devmind.worklog.service.WorklogEntryFile.DayFile;
import com.devmind.worklog.service.WorklogEntryFile.ParsedEntry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CAP-74 M3：工作条目 CRUD 的文件口径实现——条目从 DB 表迁到仓内 day 文件
 * {@code <subdir>/entries/yyyy-MM-dd.md}（格式见 {@link WorklogEntryFile}），
 * 平台单写者收口经 {@link WorklogRepoService}（仓级锁内读-改-写，与报告同语义）。
 * UI/Git 导入体验不变；条目 id 从 DB 自增 Long 改为 uuid 字符串。
 *
 * <p>按人隔离（仅能读写自己的条目，他人条目 404）；工时 API 收 hours（0.25 步进）内部存分钟。
 * 全程无 DB 事务（文件操作不参与事务）；username 显式传参的方法供 scheduler 用。</p>
 */
@Service
public class WorklogEntryService {

    public static final String TYPE_DEV = "DEV";
    public static final String TYPE_SUPPORT = "SUPPORT";
    public static final String TYPE_MEETING = "MEETING";
    public static final String TYPE_RESEARCH = "RESEARCH";
    public static final String TYPE_OTHER = "OTHER";

    public static final String SOURCE_GIT = "GIT";
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 预留：agent 会话自动转条目 */
    public static final String SOURCE_AGENT = "AGENT";

    private static final Set<String> ENTRY_TYPES = Set.of(
            TYPE_DEV, TYPE_SUPPORT, TYPE_MEETING, TYPE_RESEARCH, TYPE_OTHER);

    private final WorklogRepoService repoService;
    private final IdentityService identity;
    private final ObjectProvider<GitRepoCatalog> catalog;

    public WorklogEntryService(WorklogRepoService repoService, IdentityService identity,
                               ObjectProvider<GitRepoCatalog> catalog) {
        this.repoService = repoService;
        this.identity = identity;
        this.catalog = catalog;
    }

    /** 条目 + 归属日（范围读取/生成素材用）。 */
    public record DatedEntry(LocalDate date, ParsedEntry entry) {}

    /** 分页列表（含范围内工时合计与 git 仓库名解析）；keyword 非空时按标题模糊匹配。 */
    public EntryPage list(LocalDate from, LocalDate to, String keyword, int page, int size) {
        String me = identity.currentActor();
        String kw = keyword == null || keyword.isBlank() ? null : keyword.strip();
        List<DatedEntry> all = listRangeEntries(me, from, to).stream()
                .filter(d -> kw == null || d.entry().title().toLowerCase().contains(kw.toLowerCase()))
                .sorted(Comparator.comparing(DatedEntry::date).reversed()
                        .thenComparing((DatedEntry d) -> d.entry().createdAt(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        long totalMinutes = all.stream().mapToLong(d -> d.entry().minutes()).sum();
        List<DatedEntry> pageItems = all.stream().skip((long) page * size).limit(size).toList();
        Map<Long, String> repoNames = resolveRepoNames(pageItems.stream()
                .map(d -> d.entry().repoId()).filter(Objects::nonNull).toList());
        List<EntryView> items = pageItems.stream()
                .map(d -> EntryView.of(d.date(), d.entry(),
                        d.entry().repoId() == null ? null : repoNames.get(d.entry().repoId())))
                .toList();
        return new EntryPage(items, all.size(), totalMinutes);
    }

    public EntryView create(EntryRequest req) {
        String me = identity.currentActor();
        ParsedEntry e = newEntry(req, SOURCE_MANUAL, null, null);
        WorklogRepoEntity repo = repoService.activeRepoFor(me);
        String relName = relName(req.workDate());
        return repoService.withLock(repo.getRepoKey(), () -> {
            DayFile f = readDayFile(me, req.workDate());
            f.entries().add(e);
            repoService.writeFileTo(repo, me, relName, WorklogEntryFile.render(f),
                    "worklog: entries " + req.workDate(),
                    WorklogRepoService.CommitSource.MANUAL, null, null);
            return EntryView.of(req.workDate(), e, null);
        });
    }

    /**
     * 更新（寻址=请求体 workDate + id）。改期场景：先写新日文件再删旧日文件
     * （两个 commit 锁内串行，失败残留优于丢失）；找不到一律 404（不暴露存在性）。
     */
    public EntryView update(String id, EntryRequest req) {
        String me = identity.currentActor();
        WorklogRepoEntity repo = repoService.activeRepoFor(me);
        return repoService.withLock(repo.getRepoKey(), () -> {
            DayFile sameDay = readDayFile(me, req.workDate());
            int idx = indexOf(sameDay, id);
            if (idx >= 0) {
                ParsedEntry updated = applyTo(sameDay.entries().get(idx), req);
                sameDay.entries().set(idx, updated);
                repoService.writeFileTo(repo, me, relName(req.workDate()),
                        WorklogEntryFile.render(sameDay), "worklog: entries " + req.workDate(),
                        WorklogRepoService.CommitSource.MANUAL, null, null);
                return EntryView.of(req.workDate(), updated, null);
            }
            // 改期：全目录扫定位旧文件
            for (LocalDate d : repoService.listEntryDates(me, null, null)) {
                if (d.equals(req.workDate())) {
                    continue;
                }
                DayFile oldFile = readDayFile(me, d);
                int oldIdx = indexOf(oldFile, id);
                if (oldIdx < 0) {
                    continue;
                }
                ParsedEntry moved = applyTo(oldFile.entries().get(oldIdx), req);
                DayFile newFile = readDayFile(me, req.workDate());
                newFile.entries().add(moved);
                repoService.writeFileTo(repo, me, relName(req.workDate()),
                        WorklogEntryFile.render(newFile), "worklog: entries " + req.workDate(),
                        WorklogRepoService.CommitSource.MANUAL, null, null);
                oldFile.entries().remove(oldIdx);
                repoService.writeFileTo(repo, me, relName(d),
                        WorklogEntryFile.render(oldFile), "worklog: entries " + d,
                        WorklogRepoService.CommitSource.MANUAL, null, null);
                return EntryView.of(req.workDate(), moved, null);
            }
            throw new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id);
        });
    }

    /** 删除（API 按日期寻址：DELETE /entries/{date}/{id}，避免全目录扫描）。 */
    public void delete(LocalDate date, String id) {
        String me = identity.currentActor();
        WorklogRepoEntity repo = repoService.activeRepoFor(me);
        repoService.withLock(repo.getRepoKey(), () -> {
            DayFile f = readDayFile(me, date);
            int idx = indexOf(f, id);
            if (idx < 0) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id);
            }
            f.entries().remove(idx);
            repoService.writeFileTo(repo, me, relName(date), WorklogEntryFile.render(f),
                    "worklog: entries " + date, WorklogRepoService.CommitSource.MANUAL, null, null);
            return null;
        });
    }

    /**
     * git 提交导入为 GIT 条目。幂等：(repoId, commitSha) 已导入则跳过（判据 = 仓内条目文件
     * 元数据 sha，见 {@link #importedShas}）。按归属日分组，同日 N 条 = 1 个 commit；
     * 整批外层一次仓级锁（ReentrantLock 可重入，内层 writeFileTo 同线程直通）。
     *
     * @return [新增数, 跳过数]
     */
    public int[] importGit(String username, GitImportRequest req) {
        Set<String> imported = importedShas(username);
        Map<LocalDate, List<GitImportRequest.Item>> byDate = new LinkedHashMap<>();
        int skipped = 0;
        for (GitImportRequest.Item item : req.items()) {
            if (imported.contains(item.repoId() + ":" + item.commitSha())) {
                skipped++;
                continue;
            }
            byDate.computeIfAbsent(item.date(), d -> new ArrayList<>()).add(item);
        }
        if (byDate.isEmpty()) {
            return new int[]{0, skipped};
        }
        WorklogRepoEntity repo = repoService.activeRepoFor(username);
        int created = repoService.withLock(repo.getRepoKey(), () -> {
            int n = 0;
            for (Map.Entry<LocalDate, List<GitImportRequest.Item>> group : byDate.entrySet()) {
                DayFile f = readDayFile(username, group.getKey());
                for (GitImportRequest.Item item : group.getValue()) {
                    String subject = item.subject().length() > 256
                            ? item.subject().substring(0, 256) : item.subject();
                    f.entries().add(new ParsedEntry(WorklogEntryFile.newId(), TYPE_DEV,
                            WorklogEntryFile.toMinutes(item.hours() == null ? 0d : item.hours()),
                            SOURCE_GIT, item.repoId(), item.commitSha(), null, null,
                            Instant.now().truncatedTo(ChronoUnit.SECONDS), subject, null, null));
                    n++;
                }
                repoService.writeFileTo(repo, username, relName(group.getKey()),
                        WorklogEntryFile.render(f),
                        "worklog: entries " + group.getKey() + " (git import)",
                        WorklogRepoService.CommitSource.GIT_IMPORT, null, null);
            }
            return n;
        });
        return new int[]{created, skipped};
    }

    /** 已导入的 {@code repoId:commitSha} 集合（扫活动仓 entries 全目录；GitLogScanner 预览判重同用）。 */
    public Set<String> importedShas(String username) {
        return listRangeEntries(username, null, null).stream()
                .map(DatedEntry::entry)
                .filter(e -> e.repoId() != null && e.commitSha() != null && !e.commitSha().isBlank())
                .map(e -> e.repoId() + ":" + e.commitSha())
                .collect(Collectors.toSet());
    }

    /** 当日条目（生成素材用；无仓/无文件 → 空表）。 */
    public List<ParsedEntry> listDayEntries(String username, LocalDate date) {
        return repoService.readFileIn(username, relName(date))
                .map(c -> WorklogEntryFile.parse(c).entries())
                .orElse(List.of());
    }

    /** 范围条目（含归属日；按日期升序、日内文件顺序）。from/to 可空（null=不限）。 */
    public List<DatedEntry> listRangeEntries(String username, LocalDate from, LocalDate to) {
        List<DatedEntry> out = new ArrayList<>();
        for (LocalDate d : repoService.listEntryDates(username, from, to)) {
            for (ParsedEntry e : listDayEntries(username, d)) {
                out.add(new DatedEntry(d, e));
            }
        }
        return out;
    }

    // ---------------- 内部 ----------------

    static String relName(LocalDate date) {
        return "entries/" + date + ".md";
    }

    /** 读当日文件（无 → 带初始 preamble 的空 DayFile）；缺 id 的条目惰性补发（重写时落回）。 */
    private DayFile readDayFile(String username, LocalDate date) {
        DayFile f = repoService.readFileIn(username, relName(date))
                .map(WorklogEntryFile::parse)
                .orElse(new DayFile(WorklogEntryFile.initialPreamble(date.toString()), List.of()));
        for (int i = 0; i < f.entries().size(); i++) {
            ParsedEntry e = f.entries().get(i);
            if (e.id() == null || e.id().isBlank()) {
                f.entries().set(i, new ParsedEntry(WorklogEntryFile.newId(), e.entryType(), e.minutes(),
                        e.source(), e.repoId(), e.commitSha(), e.requirementId(), e.jiraIssueKey(),
                        e.createdAt(), e.title(), e.body(), e.extraAttrs()));
            }
        }
        return f;
    }

    private static int indexOf(DayFile f, String id) {
        for (int i = 0; i < f.entries().size(); i++) {
            if (f.entries().get(i).id() != null && f.entries().get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static ParsedEntry newEntry(EntryRequest req, String source, Long repoId, String commitSha) {
        return new ParsedEntry(WorklogEntryFile.newId(), validatedType(req.entryType()),
                WorklogEntryFile.toMinutes(req.hours()), source, repoId, commitSha,
                req.requirementId(), req.jiraIssueKey(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS), req.title().strip(), req.content(), null);
    }

    /** 就地更新可变字段；id/created/source/repo/sha/extraAttrs 保留。 */
    private static ParsedEntry applyTo(ParsedEntry old, EntryRequest req) {
        return new ParsedEntry(old.id(), validatedType(req.entryType()),
                WorklogEntryFile.toMinutes(req.hours()), old.source(), old.repoId(), old.commitSha(),
                req.requirementId(), req.jiraIssueKey(), old.createdAt(),
                req.title().strip(), req.content(), old.extraAttrs());
    }

    private static String validatedType(String type) {
        String t = type == null || type.isBlank() ? TYPE_DEV : type;
        if (!ENTRY_TYPES.contains(t)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "entryType 仅支持 " + ENTRY_TYPES);
        }
        return t;
    }

    /** repoId → 仓库名（CAP-29 全局登记表经 SPI 读，缺席时返回空表）。 */
    private Map<Long, String> resolveRepoNames(List<Long> repoIds) {
        GitRepoCatalog cat = catalog.getIfAvailable();
        if (cat == null || repoIds.isEmpty()) {
            return Map.of();
        }
        return cat.listByIds(repoIds).stream()
                .collect(Collectors.toMap(GitRepoCatalog.RepoRef::id, GitRepoCatalog.RepoRef::name, (a, b) -> a));
    }
}
