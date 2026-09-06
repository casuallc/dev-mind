package com.devmind.worklog.controller;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.GitImportRequest;
import com.devmind.worklog.dto.GitPreviewResponse;
import com.devmind.worklog.service.GitLogScanner;
import com.devmind.worklog.service.WorklogEntryService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** CAP-28 FR-04：git 提交扫描预览与导入（仅本人勾选仓库）。 */
@RestController
@RequestMapping("/api/worklog/git")
public class WorklogGitController {

    private final GitLogScanner scanner;
    private final WorklogEntryService entryService;
    private final IdentityService identity;

    public WorklogGitController(GitLogScanner scanner, WorklogEntryService entryService,
                                IdentityService identity) {
        this.scanner = scanner;
        this.entryService = entryService;
        this.identity = identity;
    }

    /** 预览：范围内提交 + 每个勾选仓库的扫描诊断（为什么某仓库没有提交出现）。范围上限 62 天。 */
    @GetMapping("/preview")
    public GitPreviewResponse preview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "范围结束日不能早于开始日");
        }
        if (from.plusDays(62).isBefore(to)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "扫描范围最长 62 天");
        }
        return scanner.scanDetailed(identity.currentActor(), from, to);
    }

    /** @return {created, skipped} */
    @PostMapping("/import")
    public Map<String, Integer> importGit(@Valid @RequestBody GitImportRequest req) {
        int[] r = entryService.importGit(identity.currentActor(), req);
        return Map.of("created", r[0], "skipped", r[1]);
    }
}
