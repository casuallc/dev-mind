package com.devmind.worklog.service;

import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.dto.GitImportRequest;
import com.devmind.worklog.dto.GitPreviewResponse;
import com.devmind.worklog.dto.GitScanRepoDiag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CAP-28 FR-09：定时自动 Git 导入核心（调度线程安全——全程显式传 username，不碰 currentActor）。
 *
 * <p>与手动导入（preview → 人工勾选）的差异只在安全口径：手动链路里署名解析失败的仓库靠
 * 「预览人工勾选」兜底，自动链路没有这个人工环节，因此<b>只导入署名过滤已生效的仓库</b>
 * （诊断 {@code outcome=SCANNED 且 authorFilter 非空}），未过滤的仓库整仓跳过防混入他人提交。</p>
 *
 * <p>幂等与手动导入完全一致：按 (user_id, repo_id, commit_sha) 去重（{@link WorklogEntryService#importGit}），
 * hours = null（0 工时，事后编辑补录，口径同手动导入）。</p>
 */
@Service
public class GitAutoImportService {

    private static final Logger log = LoggerFactory.getLogger(GitAutoImportService.class);

    private final GitLogScanner scanner;
    private final WorklogEntryService entryService;

    public GitAutoImportService(GitLogScanner scanner, WorklogEntryService entryService) {
        this.scanner = scanner;
        this.entryService = entryService;
    }

    /**
     * 扫描该用户勾选仓库在 date 当日的提交并自动导入未导入项。
     *
     * @return {created, skipped}（skipped 仅含 importGit 内部去重跳过；被安全口径剔除的不计）
     */
    public int[] importForDate(String username, LocalDate date) {
        GitPreviewResponse res = scanner.scanDetailed(username, date);
        // 可自动导入仓库集：署名过滤已生效（authorFilter 非空 = git log 带了 --author）
        Set<Long> importable = res.repos().stream()
                .filter(d -> "SCANNED".equals(d.outcome()) && d.authorFilter() != null)
                .map(GitScanRepoDiag::repoId)
                .collect(Collectors.toSet());
        List<GitImportRequest.Item> items = res.commits().stream()
                .filter(c -> !c.alreadyImported() && importable.contains(c.repoId()))
                .map(GitAutoImportService::toItem)
                .toList();
        if (items.isEmpty()) {
            return new int[]{0, 0};
        }
        int[] r = entryService.importGit(username, new GitImportRequest(items));
        if (r[0] > 0) {
            log.info("git 定时导入: user={} date={} created={} skipped={}", username, date, r[0], r[1]);
        }
        return r;
    }

    /** 条目归属日 = 提交实际日期（本机时区，与手动导入一致）；hours=null → 0 工时事后补。 */
    private static GitImportRequest.Item toItem(GitCommitView c) {
        return new GitImportRequest.Item(c.repoId(), c.sha(), c.subject(),
                LocalDate.ofInstant(c.committedAt(), ZoneId.systemDefault()), null);
    }
}
