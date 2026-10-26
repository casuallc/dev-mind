package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitRepoCatalog;
import com.devmind.worklog.dto.EntryPage;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.dto.EntryView;
import com.devmind.worklog.dto.GitImportRequest;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.repo.WorklogEntryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工作条目 CRUD（CAP-89 FR-02：DB 口径复活，CAP-28 原实现 + 主键保 String uuid）。
 * CAP-74 的仓内 day 文件口径已随 git 链路整体退役（marker 解析器仅存量迁移消费，
 * 见 {@link WorklogRepoToDbMigration}）。
 *
 * <p>按人隔离（仅能读写自己的条目，他人条目 404）；工时 API 收 hours（0.25 步进）内部存分钟。
 * username 显式传参的方法供 scheduler 用（无 SecurityContext）。</p>
 */
@Service
public class WorklogEntryService {

    public static final String TYPE_DEV = WorklogEntryEntity.TYPE_DEV;
    public static final String TYPE_SUPPORT = WorklogEntryEntity.TYPE_SUPPORT;
    public static final String TYPE_MEETING = WorklogEntryEntity.TYPE_MEETING;
    public static final String TYPE_RESEARCH = WorklogEntryEntity.TYPE_RESEARCH;
    public static final String TYPE_OTHER = WorklogEntryEntity.TYPE_OTHER;

    public static final String SOURCE_GIT = WorklogEntryEntity.SOURCE_GIT;
    public static final String SOURCE_MANUAL = WorklogEntryEntity.SOURCE_MANUAL;
    /** 预留：agent 会话自动转条目 */
    public static final String SOURCE_AGENT = WorklogEntryEntity.SOURCE_AGENT;

    private static final Set<String> ENTRY_TYPES = Set.of(
            TYPE_DEV, TYPE_SUPPORT, TYPE_MEETING, TYPE_RESEARCH, TYPE_OTHER);

    private final WorklogEntryRepository entryRepo;
    private final IdentityService identity;
    private final ObjectProvider<GitRepoCatalog> catalog;

    public WorklogEntryService(WorklogEntryRepository entryRepo, IdentityService identity,
                               ObjectProvider<GitRepoCatalog> catalog) {
        this.entryRepo = entryRepo;
        this.identity = identity;
        this.catalog = catalog;
    }

    /** 条目 + 归属日（范围读取/生成素材用；date 与 entry.workDate 恒一致，留存为调用方便利）。 */
    public record DatedEntry(LocalDate date, WorklogEntryEntity entry) {}

    /** 分页列表（含范围内工时合计与 git 仓库名解析）；keyword 非空时按标题模糊匹配（下沉服务端）。 */
    public EntryPage list(LocalDate from, LocalDate to, String keyword, int page, int size) {
        String me = identity.currentActor();
        String kw = keyword == null || keyword.isBlank() ? null : keyword.strip();
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "workDate").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<WorklogEntryEntity> result = kw == null
                ? entryRepo.findByUserIdAndWorkDateBetween(me, from, to, pageable)
                : entryRepo.findByUserIdAndWorkDateBetweenAndTitleContainingIgnoreCase(me, from, to, kw, pageable);
        long totalMinutes = entryRepo.sumMinutes(me, from, to, kw == null ? "" : kw);
        Map<Long, String> repoNames = resolveRepoNames(result.getContent().stream()
                .map(WorklogEntryEntity::getRepoId).filter(Objects::nonNull).toList());
        List<EntryView> items = result.getContent().stream()
                .map(e -> EntryView.of(e, e.getRepoId() == null ? null : repoNames.get(e.getRepoId())))
                .toList();
        return new EntryPage(items, result.getTotalElements(), totalMinutes);
    }

    public EntryView create(EntryRequest req) {
        String me = identity.currentActor();
        WorklogEntryEntity e = new WorklogEntryEntity();
        e.setId(newId());
        e.setUserId(me);
        e.setSource(SOURCE_MANUAL);
        e.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        applyTo(e, req);
        return EntryView.of(entryRepo.save(e), null);
    }

    /** 更新（DB 主键直接定位；改期只是改 work_date 列）。他人条目 404（不暴露存在性）。 */
    public EntryView update(String id, EntryRequest req) {
        String me = identity.currentActor();
        WorklogEntryEntity e = entryRepo.findByIdAndUserId(id, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id));
        applyTo(e, req);
        return EntryView.of(entryRepo.save(e), null);
    }

    /** 删除（CAP-89 FR-06：DB 主键直接定位，date 寻址是文件时代的限制，已回收）。 */
    public void delete(String id) {
        String me = identity.currentActor();
        WorklogEntryEntity e = entryRepo.findByIdAndUserId(id, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id));
        entryRepo.delete(e);
    }

    /**
     * git 提交导入为 GIT 条目。幂等：(userId, repoId, commitSha) DB 唯一约束判重
     * （判据见 {@link #importedShas}）。
     *
     * @return [新增数, 跳过数]
     */
    public int[] importGit(String username, GitImportRequest req) {
        Set<String> imported = importedShas(username);
        int created = 0;
        int skipped = 0;
        for (GitImportRequest.Item item : req.items()) {
            if (imported.contains(item.repoId() + ":" + item.commitSha())) {
                skipped++;
                continue;
            }
            WorklogEntryEntity e = new WorklogEntryEntity();
            e.setId(newId());
            e.setUserId(username);
            e.setWorkDate(item.date());
            e.setTitle(item.subject().length() > 256 ? item.subject().substring(0, 256) : item.subject());
            e.setEntryType(TYPE_DEV);
            e.setMinutes(WorklogEntryFile.toMinutes(item.hours() == null ? 0d : item.hours()));
            e.setSource(SOURCE_GIT);
            e.setRepoId(item.repoId());
            e.setCommitSha(item.commitSha());
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            entryRepo.save(e);
            created++;
        }
        return new int[]{created, skipped};
    }

    /** 已导入的 {@code repoId:commitSha} 集合（DB 直查；GitLogScanner 预览判重同用）。 */
    public Set<String> importedShas(String username) {
        return entryRepo.findGitKeys(username).stream()
                .map(row -> row[0] + ":" + row[1])
                .collect(Collectors.toSet());
    }

    /** 当日条目（生成素材用；无 → 空表）。 */
    public List<WorklogEntryEntity> listDayEntries(String username, LocalDate date) {
        return entryRepo.findByUserIdAndWorkDateOrderByCreatedAtAsc(username, date);
    }

    /** 范围条目（含归属日；按日期升序、日内创建时间序）。from/to 可空（null=不限）。 */
    public List<DatedEntry> listRangeEntries(String username, LocalDate from, LocalDate to) {
        return entryRepo.findRange(username, from, to).stream()
                .map(e -> new DatedEntry(e.getWorkDate(), e))
                .toList();
    }

    // ---------------- 内部 ----------------

    /** 条目主键：uuid 32hex（应用侧生成；文件时代与 DB 时代共用同一形态）。 */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 就地更新可变字段；id/userId/source/repo/sha/createdAt 保留。 */
    private static void applyTo(WorklogEntryEntity e, EntryRequest req) {
        e.setWorkDate(req.workDate());
        e.setTitle(req.title().strip());
        e.setContent(req.content());
        e.setEntryType(validatedType(req.entryType()));
        e.setMinutes(WorklogEntryFile.toMinutes(req.hours()));
        e.setRequirementId(req.requirementId());
        e.setJiraIssueKey(req.jiraIssueKey());
        e.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
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
