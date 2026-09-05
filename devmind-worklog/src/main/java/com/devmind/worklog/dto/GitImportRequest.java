package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/** git 导入请求：preview 勾选后的子集；hours 可空（默认 0，导入后再编辑补工时）。 */
public record GitImportRequest(
        @NotNull LocalDate date,
        @NotNull List<Item> items) {

    public record Item(@NotNull Long repoId, @NotNull String commitSha,
                       @NotNull String subject, Double hours) {}
}
