package com.devmind.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** CAP-70：出口规则新建/编辑请求 */
public record EgressRuleRequest(
        @NotBlank String hostPattern,
        @NotNull Long nodeId,
        Boolean enabled,
        Integer sort,
        String remark) {
}
