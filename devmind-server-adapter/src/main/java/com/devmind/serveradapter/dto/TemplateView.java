package com.devmind.serveradapter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 命令模板视图：params 为解析后的参数 schema（前端据此动态渲染表单）。
 */
public record TemplateView(
        Long id,
        String projectId,
        String code,
        String name,
        String templateText,
        List<Param> params,
        List<String> allowed,
        Instant createdAt,
        Instant updatedAt) {

    public record Param(String name, Boolean required, String label, String defaultValue) {
    }
}
