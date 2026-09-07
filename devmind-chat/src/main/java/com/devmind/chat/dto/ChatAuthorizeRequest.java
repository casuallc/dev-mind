package com.devmind.chat.dto;

/**
 * 问答授权响应请求。布尔字段用包装类型（Jackson 3：null→primitive 直接抛错）。
 */
public record ChatAuthorizeRequest(Boolean accepted, String scope, String requestId) {

    public boolean acceptedOrFalse() {
        return Boolean.TRUE.equals(accepted);
    }
}
