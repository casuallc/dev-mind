package com.devmind.serveradapter.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令模板（CAP-07 FR-05 白名单）：code 是远程执行唯一可引用的入口。
 * 渲染：模板正文中 ${param} 由 params 替换；缺必填参数在 Service 层校验，未给值但 schema 有默认值时用默认值。
 */
public record ScriptTemplate(
        Long id,
        String projectId,
        String code,
        String name,
        String templateText,
        List<ParamSpec> params,
        Set<String> allowed) {

    /** 参数 schema 单项 */
    public record ParamSpec(String name, boolean required, String label, String defaultValue) {
    }

    public String render(Map<String, String> values) {
        String out = templateText == null ? "" : templateText;
        for (ParamSpec p : params) {
            String v = values != null ? values.get(p.name()) : null;
            if ((v == null || v.isBlank()) && p.defaultValue() != null) {
                v = p.defaultValue();
            }
            if (v == null) {
                v = "";
            }
            out = out.replace("${" + p.name() + "}", v);
        }
        return out;
    }

    public boolean allows(String capability) {
        return allowed == null || allowed.isEmpty() || allowed.contains(capability);
    }
}
