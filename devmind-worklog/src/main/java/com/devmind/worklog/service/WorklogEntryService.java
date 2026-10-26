package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitRepoCatalog;
import com.devmind.worklog.dto.EntryPage;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.dto.EntryView;
import com.devmind.worklog.dto.EntryCarryResult;
import com.devmind.worklog.dto.GitImportRequest;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.model.WorklogPlanItemEntity;
import com.devmind.worklog.repo.WorklogEntryRepository;
import com.devmind.worklog.repo.WorklogPlanItemRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

    /** CAP-90 FR-02：人工可设状态（CARRIED 仅滚转写入） */
    private static final Set<String> MANUAL_STATUSES = Set.of(
            WorklogEntryEntity.STATUS_TODO, WorklogEntryEntity.STATUS_DOING,
            WorklogEntryEntity.STATUS_DONE, WorklogEntryEntity.STATUS_DROPPED);
    /** 未完成（可滚转）状态集 */
    static final Set<String> UNFINISHED = Set.of(
            WorklogEntryEntity.STATUS_TODO, WorklogEntryEntity.STATUS_DOING);

    private final WorklogEntryRepository entryRepo;
    private final WorklogPlanItemRepository planItemRepo;
    private final IdentityService identity;
    private final ObjectProvider<GitRepoCatalog> catalog;

    public WorklogEntryService(WorklogEntryRepository entryRepo, WorklogPlanItemRepository planItemRepo,
                               IdentityService identity, ObjectProvider<GitRepoCatalog> catalog) {
        this.entryRepo = entryRepo;
        this.planItemRepo = planItemRepo;
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
        applyPlanLink(e, req.planId(), me);
        return EntryView.of(entryRepo.save(e), null);
    }

    /** 更新（DB 主键直接定位；改期只是改 work_date 列）。他人条目 404（不暴露存在性）。
     *  CAP-90 FR-02：GIT/AGENT 来源条目恒 DONE（事实），不可转非 DONE。 */
    public EntryView update(String id, EntryRequest req) {
        String me = identity.currentActor();
        WorklogEntryEntity e = entryRepo.findByIdAndUserId(id, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id));
        if ((SOURCE_GIT.equals(e.getSource()) || SOURCE_AGENT.equals(e.getSource()))
                && req.status() != null && !WorklogEntryEntity.STATUS_DONE.equals(req.status().strip())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    e.getSource() + " 来源条目是已发生事实，状态恒为 DONE");
        }
        applyTo(e, req);
        applyPlanLink(e, req.planId(), me);
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

    /** 当日条目（生成素材用，CAP-90 FR-04：只取 DONE 事实条目；无 → 空表）。 */
    public List<WorklogEntryEntity> listDayEntries(String username, LocalDate date) {
        return entryRepo.findByUserIdAndWorkDateAndStatusInOrderByCreatedAtAsc(
                username, date, Set.of(WorklogEntryEntity.STATUS_DONE));
    }

    /** 范围条目（含归属日；按日期升序、日内创建时间序）。from/to 可空（null=不限）。
     *  CAP-90 FR-04：素材口径只取 DONE 事实条目。 */
    public List<DatedEntry> listRangeEntries(String username, LocalDate from, LocalDate to) {
        return entryRepo.findRange(username, from, to).stream()
                .filter(e -> WorklogEntryEntity.STATUS_DONE.equals(e.getStatus()))
                .map(e -> new DatedEntry(e.getWorkDate(), e))
                .toList();
    }

    /** 上一日未完成（TODO/DOING）条目提示（CAP-90 FR-05：日粒度滚转的提示数据，沿用 plans carryover 形态）。 */
    public List<EntryView> dayCarryover(LocalDate date) {
        String me = identity.currentActor();
        return entryRepo.findByUserIdAndWorkDateAndStatusInOrderByCreatedAtAsc(
                        me, date.minusDays(1), UNFINISHED).stream()
                .map(e -> EntryView.of(e, null))
                .toList();
    }

    /**
     * 条目日粒度一键滚转（CAP-90 FR-05）：目标日插入各条目副本（新 uuid、status=TODO、estimate 沿用、
     * carried_from_id=原 uuid、minutes 归零），原行置 CARRIED——单事务完成。
     * 幂等：原行已 CARRIED 或已完结（DONE/DROPPED）即跳过；GIT/AGENT 来源恒 DONE 天然不可滚转。
     */
    @Transactional
    public EntryCarryResult carry(LocalDate to, List<String> ids) {
        String me = identity.currentActor();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<EntryView> created = new ArrayList<>();
        int skipped = 0;
        for (String id : ids) {
            WorklogEntryEntity src = entryRepo.findByIdAndUserId(id, me)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id));
            if (!UNFINISHED.contains(src.getStatus())) {
                skipped++;
                continue;
            }
            WorklogEntryEntity copy = new WorklogEntryEntity();
            copy.setId(newId());
            copy.setUserId(me);
            copy.setWorkDate(to);
            copy.setTitle(src.getTitle());
            copy.setContent(src.getContent());
            copy.setEntryType(src.getEntryType());
            copy.setMinutes(0);
            copy.setEstimateMinutes(src.getEstimateMinutes());
            copy.setStatus(WorklogEntryEntity.STATUS_TODO);
            copy.setSource(src.getSource());
            copy.setRequirementId(src.getRequirementId());
            copy.setJiraIssueKey(src.getJiraIssueKey());
            copy.setPlanItemId(src.getPlanItemId());
            copy.setCarriedFromId(src.getId());
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            src.setStatus(WorklogEntryEntity.STATUS_CARRIED);
            src.setUpdatedAt(now);
            entryRepo.save(src);
            created.add(EntryView.of(entryRepo.save(copy), null));
        }
        return new EntryCarryResult(created.size(), skipped, created);
    }

    // ---------------- 内部 ----------------

    /** 条目主键：uuid 32hex（应用侧生成；文件时代与 DB 时代共用同一形态）。 */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 就地更新可变字段；id/userId/source/repo/sha/carriedFromId/createdAt 保留。
     *  status 空 = 不动（新建走实体默认 DONE）；CARRIED 人工不可设（仅滚转写入）。 */
    private static void applyTo(WorklogEntryEntity e, EntryRequest req) {
        e.setWorkDate(req.workDate());
        e.setTitle(req.title().strip());
        e.setContent(req.content());
        e.setEntryType(validatedType(req.entryType()));
        e.setMinutes(WorklogEntryFile.toMinutes(req.hours()));
        if (req.status() != null && !req.status().isBlank()) {
            String st = req.status().strip();
            if (!MANUAL_STATUSES.contains(st)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "status 仅支持 " + MANUAL_STATUSES + "（CARRIED 由滚转写入）");
            }
            e.setStatus(st);
        }
        e.setEstimateMinutes(req.estimateHours() == null ? null : WorklogEntryFile.toMinutes(req.estimateHours()));
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

    /**
     * 条目关联计划（CAP-88 FR-04；CAP-90 FR-06：仅 WEEK 项可关联——日计划已并入条目，日执行
     * 直接对齐周方向）：planId 必须是本人 WEEK 项（他人/不存在 404，DAY 项 400）；关联时该项为
     * TODO 则服务端置 DOING（关联只证明动过，不证明做完——DONE 必须人工）。null=解除关联。
     */
    private void applyPlanLink(WorklogEntryEntity e, Long planId, String me) {
        if (planId == null) {
            e.setPlanItemId(null);
            return;
        }
        WorklogPlanItemEntity item = planItemRepo.findByIdAndUserId(planId, me)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "计划项不存在: " + planId));
        if (!WorklogPlanItemEntity.SCOPE_WEEK.equals(item.getScope())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "日计划已并入条目（CAP-90），条目只能关联周计划项");
        }
        e.setPlanItemId(planId);
        if (WorklogPlanItemEntity.STATUS_TODO.equals(item.getStatus())) {
            item.setStatus(WorklogPlanItemEntity.STATUS_DOING);
            item.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            planItemRepo.save(item);
        }
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
