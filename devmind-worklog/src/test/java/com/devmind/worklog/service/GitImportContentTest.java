package com.devmind.worklog.service;

import com.devmind.worklog.dto.GitImportRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GIT 导入条目详情合成：提交正文 + 改动规模行（WorklogEntryService.composeGitContent）。 */
class GitImportContentTest {

    private static GitImportRequest.Item item(String body, Integer files, Integer ins, Integer del) {
        return new GitImportRequest.Item(1L, "abc123", "s", LocalDate.now(), null,
                body, files, ins, del);
    }

    @Test
    void bodyAndStatsComposed() {
        assertEquals("修复空指针\n补充单测\n\n改动规模：3 个文件，+120 -45",
                WorklogEntryService.composeGitContent(item("修复空指针\n补充单测", 3, 120, 45)));
    }

    @Test
    void bodyOnly() {
        assertEquals("仅正文", WorklogEntryService.composeGitContent(item("仅正文", null, null, null)));
        // filesChanged=0 不出规模行
        assertEquals("仅正文", WorklogEntryService.composeGitContent(item("仅正文", 0, 0, 0)));
    }

    @Test
    void statsOnlyWhenBodyBlank() {
        assertEquals("改动规模：2 个文件，+5 -1",
                WorklogEntryService.composeGitContent(item("  ", 2, 5, 1)));
    }

    @Test
    void emptyWhenNothingProvided() {
        assertNull(WorklogEntryService.composeGitContent(item(null, null, null, null)));
        assertNull(WorklogEntryService.composeGitContent(item("", 0, 0, 0)));
    }

    @Test
    void longBodyTruncated() {
        String body = "x".repeat(5000);
        String content = WorklogEntryService.composeGitContent(item(body, 1, 1, 0));
        assertTrue(content.startsWith("x".repeat(4000)));
        assertEquals(4000 + "\n\n改动规模：1 个文件，+1 -0".length(), content.length());
    }
}
