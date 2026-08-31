package com.devmind.deploy.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** 部署计划步骤写入（CAP-09 FR-01）：templateCode 对应 CAP-07 模板白名单 code。 */
public record DeployStepRequest(
        @NotBlank String name,
        @NotBlank String type,
        @NotBlank String templateCode,
        Map<String, String> params) {
}
