package com.devmind.agent.dto;

import jakarta.validation.constraints.NotBlank;

/** 创建节点请求。labels 逗号分隔（调度预留）。 */
public record CreateAgentNodeRequest(@NotBlank(message = "name 不能为空") String name, String labels) {
}
