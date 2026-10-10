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
        List<String> secrets,
        /** CAP-73：补丁验证部署目录（空 = 不可用于验证） */
        String patchDir,
        /** CAP-73：覆盖文件前命令（停服/备份） */
        String patchPreHook,
        /** CAP-73：覆盖文件后命令（启服/热加载） */
        String patchPostHook) {
}
