package com.devmind.notification.dto;

/**
 * 快捷动作请求（FR-04）：{action: "authorize"|"deny"|"finish"|"view"|…}
 */
public record ActionRequest(String action) {
}
