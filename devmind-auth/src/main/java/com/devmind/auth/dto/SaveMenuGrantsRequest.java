package com.devmind.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** CAP-61 FR-04 全量覆盖某角色的菜单授权（先删后插）。 */
public record SaveMenuGrantsRequest(@NotBlank String role, @NotNull List<String> menuKeys) {
}
