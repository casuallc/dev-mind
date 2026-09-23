package com.devmind.classify.instance.dto;

import java.time.Instant;
import java.util.Map;

/**
 * CAP-57 实例视图：env 与最近健康快照已解析成 Map（前端直接渲染槽位/设备/sources），
 * 时间走全局 Jackson 格式（yyyy-MM-dd HH:mm:ss）。
 */
public record ClassifyInstanceView(Long id, String name, String agentNodeId, Integer port, String baseUrl,
                                   Long appPackageId, String pythonBin, Map<String, String> env,
                                   String commandOverride, String status, Instant lastStartAt,
                                   Instant lastHealthAt, Map<String, Object> lastHealth, String lastError,
                                   String createdBy, Instant createdAt, Instant updatedAt) {
}
