package com.devmind.worklog.dto;

import java.util.List;

/** git 扫描预览响应：提交列表 + 每个勾选仓库的扫描诊断。 */
public record GitPreviewResponse(List<GitCommitView> commits, List<GitScanRepoDiag> repos) {}
