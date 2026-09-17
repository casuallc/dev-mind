package com.devmind.worklog.controller;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.worklog.dto.GitCommitView;
import com.devmind.worklog.dto.GitPreviewResponse;
import com.devmind.worklog.dto.GitScanRepoDiag;
import com.devmind.worklog.service.GitLogScanner;
import com.devmind.worklog.service.WorklogEntryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** preview 的 filter 参数（NEW/IMPORTED/ALL）后端过滤逻辑。 */
class WorklogGitControllerFilterTest {

    private final GitLogScanner scanner = mock(GitLogScanner.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final WorklogGitController controller =
            new WorklogGitController(scanner, mock(WorklogEntryService.class), identity);

    private static GitCommitView commit(boolean imported) {
        return new GitCommitView(1L, "repo", imported ? "aaa" : "bbb", "n", "e",
                Instant.parse("2026-09-17T01:00:00Z"), "s", imported);
    }

    private void stubPreview() {
        when(identity.currentActor()).thenReturn("u");
        List<GitScanRepoDiag> diags = List.of(new GitScanRepoDiag(1L, "repo", "SCANNED", null, null, 2));
        when(scanner.scanDetailed(eq("u"), any(), any()))
                .thenReturn(new GitPreviewResponse(List.of(commit(false), commit(true)), diags));
    }

    @Test
    void filterNewReturnsOnlyNotImported() {
        stubPreview();
        var res = controller.preview(LocalDate.now(), LocalDate.now(), "NEW");
        assertEquals(1, res.commits().size());
        assertEquals("bbb", res.commits().get(0).sha());
        assertEquals(1, res.repos().size()); // 诊断不受 filter 影响
    }

    @Test
    void filterImportedReturnsOnlyImported() {
        stubPreview();
        var res = controller.preview(LocalDate.now(), LocalDate.now(), "imported"); // 大小写不敏感
        assertEquals(1, res.commits().size());
        assertEquals("aaa", res.commits().get(0).sha());
    }

    @Test
    void filterAllReturnsEverything() {
        stubPreview();
        var res = controller.preview(LocalDate.now(), LocalDate.now(), "ALL");
        assertEquals(2, res.commits().size());
        assertEquals(1, res.repos().size());
    }

    @Test
    void bogusFilterRejected() {
        stubPreview();
        assertThrows(DevMindException.class,
                () -> controller.preview(LocalDate.now(), LocalDate.now(), "BOGUS"));
    }
}
