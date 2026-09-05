package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.EntryRequest;
import com.devmind.worklog.dto.EntryView;
import com.devmind.worklog.dto.GitImportRequest;
import com.devmind.worklog.model.WorklogEntryEntity;
import com.devmind.worklog.repo.WorklogEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * CAP-28 FR-03：工作条目 CRUD，按人隔离（仅能读写自己的条目，他人条目 404）。
 * 工时：API 收 hours（小时，0.25 步进），内部存 minutes。
 */
@Service
public class WorklogEntryService {

    private static final Set<String> ENTRY_TYPES = Set.of(
            WorklogEntryEntity.TYPE_DEV, WorklogEntryEntity.TYPE_SUPPORT,
            WorklogEntryEntity.TYPE_MEETING, WorklogEntryEntity.TYPE_RESEARCH,
            WorklogEntryEntity.TYPE_OTHER);

    private final WorklogEntryRepository entryRepo;
    private final IdentityService identity;

    public WorklogEntryService(WorklogEntryRepository entryRepo, IdentityService identity) {
        this.entryRepo = entryRepo;
        this.identity = identity;
    }

    public List<EntryView> list(LocalDate from, LocalDate to) {
        String me = identity.currentActor();
        return entryRepo.findByUserIdAndWorkDateBetweenOrderByWorkDateAscIdAsc(me, from, to)
                .stream().map(EntryView::of).toList();
    }

    @Transactional
    public EntryView create(EntryRequest req) {
        WorklogEntryEntity e = new WorklogEntryEntity();
        e.setUserId(identity.currentActor());
        apply(e, req);
        e.setSource(WorklogEntryEntity.SOURCE_MANUAL);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return EntryView.of(entryRepo.save(e));
    }

    @Transactional
    public EntryView update(Long id, EntryRequest req) {
        WorklogEntryEntity e = mine(id);
        apply(e, req);
        e.setUpdatedAt(Instant.now());
        return EntryView.of(entryRepo.save(e));
    }

    @Transactional
    public void delete(Long id) {
        entryRepo.delete(mine(id));
    }

    /**
     * CAP-28 FR-04：git 提交导入为 GIT 条目。幂等：(user, repo, commit_sha) 已存在则跳过。
     *
     * @return [新增数, 跳过数]
     */
    @Transactional
    public int[] importGit(String username, GitImportRequest req) {
        int created = 0, skipped = 0;
        for (GitImportRequest.Item item : req.items()) {
            if (entryRepo.existsByUserIdAndRepoIdAndCommitSha(username, item.repoId(), item.commitSha())) {
                skipped++;
                continue;
            }
            WorklogEntryEntity e = new WorklogEntryEntity();
            e.setUserId(username);
            e.setWorkDate(req.date());
            e.setTitle(item.subject().length() > 256 ? item.subject().substring(0, 256) : item.subject());
            e.setEntryType(WorklogEntryEntity.TYPE_DEV);
            e.setMinutes(toMinutes(item.hours() == null ? 0d : item.hours()));
            e.setSource(WorklogEntryEntity.SOURCE_GIT);
            e.setRepoId(item.repoId());
            e.setCommitSha(item.commitSha());
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            entryRepo.save(e);
            created++;
        }
        return new int[]{created, skipped};
    }

    /** 仅本人可见可改；他人条目一律 404（不暴露存在性）。 */
    WorklogEntryEntity mine(Long id) {
        return entryRepo.findByIdAndUserId(id, identity.currentActor())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "条目不存在: " + id));
    }

    static int toMinutes(Double hours) {
        // 0.25h 步进由前端约束；服务端按 15 分钟粒度四舍五入兜底
        return (int) Math.round(hours * 60 / 15.0) * 15;
    }

    private void apply(WorklogEntryEntity e, EntryRequest req) {
        String type = req.entryType() == null || req.entryType().isBlank()
                ? WorklogEntryEntity.TYPE_DEV : req.entryType();
        if (!ENTRY_TYPES.contains(type)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "entryType 仅支持 " + ENTRY_TYPES);
        }
        e.setWorkDate(req.workDate());
        e.setTitle(req.title().strip());
        e.setContent(req.content());
        e.setEntryType(type);
        e.setMinutes(toMinutes(req.hours()));
        e.setRequirementId(req.requirementId());
        e.setJiraIssueKey(req.jiraIssueKey());
    }
}
