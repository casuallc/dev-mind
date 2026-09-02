package com.devmind.project;

import java.util.Collection;
import java.util.Map;

/**
 * 需求外部引用查找端口（project → integration 反向依赖解耦）：
 * RequirementView 组装时批量带出 externalUrl/remoteStatus（externalKey 在 requirements 表冗余列上）。
 * integration 模块提供实现（JiraRequirementRefLookup）；缺席时视图为 null。
 */
public interface RequirementExternalRefLookup {

    /** 外部引用（Jira issue 链接 + 远端状态） */
    record ExternalRef(String externalUrl, String remoteStatus) {
    }

    /** 批量反查：key=requirementId，无外部引用的 id 不在返回 map 中 */
    Map<String, ExternalRef> refsFor(Collection<String> requirementIds);
}
