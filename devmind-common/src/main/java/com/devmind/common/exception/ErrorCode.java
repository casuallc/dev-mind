package com.devmind.common.exception;

/**
 * 统一错误码。HTTP 状态 + 业务编码 + 提示文案。（common 零依赖，HTTP 状态用 int）
 */
public enum ErrorCode {

    BAD_REQUEST(400, "DEV-400", "请求参数错误"),
    UNAUTHORIZED(401, "DEV-401", "未认证或登录已过期"),
    FORBIDDEN(403, "DEV-403", "无权限执行"),
    NOT_FOUND(404, "DEV-404", "资源不存在"),
    CONFLICT(409, "DEV-409", "状态冲突"),
    TOO_MANY_SESSIONS(429, "DEV-429", "并发会话数已达上限，请稍后再试"),
    INTERNAL(500, "DEV-500", "内部错误");

    private final int status;
    private final String code;
    private final String message;

    ErrorCode(int status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
