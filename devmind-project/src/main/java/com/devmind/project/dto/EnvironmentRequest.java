package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record EnvironmentRequest(
        @NotBlank String name,
        String description,
        /** 目标 runner 节点 id 列表（CAP-36：agent_nodes 表 id，字符串） */
        List<String> nodeIds,
        Map<String, String> variables,
        List<String> secrets) {
}
