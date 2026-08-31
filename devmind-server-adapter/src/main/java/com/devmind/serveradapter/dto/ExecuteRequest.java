package com.devmind.serveradapter.dto;

import java.util.Map;

/**
 * 执行模板请求（CAP-07 FR-05）：templateCode 白名单内才允许，params 填充 ${占位符}。
 */
public record ExecuteRequest(
        String templateCode,
        Map<String, String> params,
        String capability) {
}
