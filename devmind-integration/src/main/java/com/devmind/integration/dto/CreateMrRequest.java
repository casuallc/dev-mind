package com.devmind.integration.dto;

/**
 * 创建 MR 请求（可空字段均有默认：targetBranch=仓库默认分支，title=WI 标题）。
 */
public record CreateMrRequest(String targetBranch, String title) {}
