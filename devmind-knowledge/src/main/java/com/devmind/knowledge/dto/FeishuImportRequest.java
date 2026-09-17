package com.devmind.knowledge.dto;

import java.util.List;

/**
 * CAP-45 飞书导入请求。integrationId 用包装类型（Jackson 3 红线：null 不炸）。
 */
public record FeishuImportRequest(Long integrationId, List<String> urls) {
}
