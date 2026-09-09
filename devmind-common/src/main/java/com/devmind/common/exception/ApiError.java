package com.devmind.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * REST 错误响应体。
 * stackTrace 仅本地排错用（devmind.error.include-stacktrace 开启时由 GlobalExceptionHandler 填充），
 * null 时不序列化，线上响应体保持不变。
 */
public record ApiError(String code, String message, String path, Instant timestamp,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String stackTrace) {

    public static ApiError of(ErrorCode code, String message, String path) {
        return new ApiError(code.getCode(), message, path, Instant.now(), null);
    }

    public static ApiError of(ErrorCode code, String message, String path, String stackTrace) {
        return new ApiError(code.getCode(), message, path, Instant.now(), stackTrace);
    }
}
