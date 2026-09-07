package com.devmind.attachment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** CAP-32 切换附件可见范围请求。 */
public record ScopeUpdateRequest(
        @NotBlank @Pattern(regexp = "PRIVATE|SHARED", message = "scope 只能是 PRIVATE 或 SHARED")
        String scope) {
}
