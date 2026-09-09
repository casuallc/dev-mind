package com.devmind.execution.template;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令模板（白名单，原 CAP-07 FR-05，CAP-36 起由执行底座承接）：code 是模板执行唯一可引用的入口。
 * 渲染：模板正文中 ${param} 由 params 替换；缺必填参数在 Service 层校验，未给值但 schema 有默认值时用默认值。
 * 渲染结果（完整命令串）由服务端经 exec 帧下发 runner 节点执行（CAP-36），模板本身不出服务端。
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
