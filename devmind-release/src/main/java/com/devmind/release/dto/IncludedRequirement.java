package com.devmind.release.dto;

/**
 * 发版归集的需求引用（includedRefs 中 REQ-<seq> 解析结果；详情抽屉展示/回链用）。
 */
public record IncludedRequirement(String id, String code, String title, String status) {
}
