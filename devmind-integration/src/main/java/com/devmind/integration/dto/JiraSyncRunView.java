package com.devmind.integration.dto;

/**
 * 一次同步运行的结果（手动触发或定时轮询共用）。
 * imported=新建需求数，updated=刷新 DRAFT 需求数，skipped=已导入且不再覆盖数，
 * pages=拉取页数，error 非空表示本轮失败。
 */
public record JiraSyncRunView(Long configId, int imported, int updated, int skipped,
                              int pages, String error) {}
