package com.devmind.worklog.service;

import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.dto.GitImportRequest;
import com.devmind.worklog.dto.GitPreviewResponse;
import com.devmind.worklog.dto.GitScanRepoDiag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-09 定时自动导入安全口径：只导入「未导入 + 署名过滤已生效（diag.authorFilter 非空）仓库」的提交；
 * 无候选不调 importGit。无 mockito 依赖：scanner/entryService 匿名子类覆写。
 */
class GitAutoImportServiceTest {

    private static final LocalDate DAY = LocalDate.now();

    private static GitCommitView commit(long repoId, String sha, boolean imported, Instant at) {
        return new GitCommitView(repoId, "repo" + repoId, sha, "n", "n@e", at, "subject-" + sha, imported);
    }

    private static GitAutoImportService service(GitPreviewResponse scan, AtomicReference<GitImportRequest> captured,
                                                int[] importResult) {
        GitLogScanner scanner = new GitLogScanner(null, null, null, null) {
            @Override
            public GitPreviewResponse scanDetailed(String username, LocalDate date) {
                return scan;
            }
        };
        WorklogEntryService entryService = new WorklogEntryService(null, null, null) {
            @Override
            public int[] importGit(String username, GitImportRequest req) {
                captured.set(req);
                return importResult;
            }
        };
        return new GitAutoImportService(scanner, entryService);
    }

    @Test
    void importsOnlyNewCommitsFromAuthorFilteredRepos() {
        Instant at = DAY.atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant();
        GitPreviewResponse scan = new GitPreviewResponse(
                List.of(
                        commit(1L, "aaa", false, at),   // 导入
                        commit(1L, "bbb", true, at),    // 已导入 → 跳过
                        commit(2L, "ccc", false, at)),  // 署名未解析仓库 → 整仓跳过
                List.of(
                        new GitScanRepoDiag(1L, "repo1", "SCANNED", "me@e.com", null, 2),
                        new GitScanRepoDiag(2L, "repo2", "SCANNED", null, "未解析到署名，未按作者过滤", 1)));
        AtomicReference<GitImportRequest> captured = new AtomicReference<>();
        int[] r = service(scan, captured, new int[]{1, 0}).importForDate("u1", DAY);

        assertEquals(1, r[0]);
        List<GitImportRequest.Item> items = captured.get().items();
        assertEquals(1, items.size());
        GitImportRequest.Item it = items.get(0);
        assertEquals(1L, it.repoId());
        assertEquals("aaa", it.commitSha());
        assertEquals("subject-aaa", it.subject());
        assertEquals(DAY, it.date());          // 归属日 = 提交时间本机日期
        assertNull(it.hours());                // hours=null → 0 工时事后补
    }

    @Test
    void noCandidatesSkipsImport() {
        GitPreviewResponse scan = new GitPreviewResponse(
                List.of(commit(1L, "aaa", true, Instant.now())),
                List.of(new GitScanRepoDiag(1L, "repo1", "SCANNED", "me@e.com", null, 1)));
        AtomicReference<GitImportRequest> captured = new AtomicReference<>();
        int[] r = service(scan, captured, new int[]{9, 9}).importForDate("u1", DAY);

        assertTrue(captured.get() == null, "无候选不应调 importGit");
        assertEquals(0, r[0]);
        assertEquals(0, r[1]);
    }

    @Test
    void skippedAndFailedReposAreNotImported() {
        Instant at = DAY.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant();
        GitPreviewResponse scan = new GitPreviewResponse(
                List.of(commit(3L, "ddd", false, at), commit(4L, "eee", false, at)),
                List.of(
                        new GitScanRepoDiag(3L, "repo3", "SKIPPED", null, "仓库已停用", 0),
                        new GitScanRepoDiag(4L, "repo4", "FAILED", null, "扫描异常", 0)));
        AtomicReference<GitImportRequest> captured = new AtomicReference<>();
        int[] r = service(scan, captured, new int[]{0, 0}).importForDate("u1", DAY);

        assertTrue(captured.get() == null, "SKIPPED/FAILED 仓库不应有提交进入导入");
        assertEquals(0, r[0]);
    }
}
