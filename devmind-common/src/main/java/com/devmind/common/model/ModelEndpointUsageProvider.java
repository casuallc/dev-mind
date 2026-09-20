package com.devmind.common.model;

import java.util.List;

/**
 * CAP-48 FR-01 端点引用方查询（删除保护）：devmind-model 删除端点前必须知道谁在用。
 * 引用方（如 knowledge_bases.model_endpoint_id）是各消费模块的自有数据，
 * 因此由消费模块实现本 SPI、devmind-model 以 {@code ObjectProvider} 探测注入。
 *
 * <p>未装配实现 = 该消费模块不在组装清单里 = 不可能有引用，删除放行。</p>
 */
public interface ModelEndpointUsageProvider {

    /**
     * 列出引用该端点的资源描述（如「知识库：前端规范」）。
     *
     * @return 空列表 = 无引用，可安全删除
     */
    List<String> usagesOf(long endpointId);
}
