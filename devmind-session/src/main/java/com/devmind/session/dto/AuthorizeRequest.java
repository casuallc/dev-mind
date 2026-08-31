package com.devmind.session.dto;

/**
 * 授权响应。
 *
 * @param accepted 是否允许
 * @param scope    允许时范围：once / session / always
 * @param requestId 目标 permission_request 的 id（缺省取最近一次挂起请求）
 */
public record AuthorizeRequest(boolean accepted, String scope, String requestId) {
}
