package com.devmind.execution.template;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 命令模板 CRUD 请求（白名单）。params 为参数 schema，服务端序列化存 JSON。
 */
public record TemplateRequest(
        String projectId,
        @NotBlank String code,
        @NotBlank String name,
        String templateText,
        List<Param> params,
        List<String> allowed) {

    public record Param(String name, Boolean required, String label, String defaultValue) {
    }
}
