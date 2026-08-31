package com.devmind.project.dto;

/**
 * 解决方案创建/更新请求。version 自动递增，无需传入。
 */
public record DesignRequest(
        Long docId) {
}
