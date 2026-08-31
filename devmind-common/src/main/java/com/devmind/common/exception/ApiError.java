package com.devmind.common.exception;

import java.time.Instant;

/**
 * REST 错误响应体。
 */
public record ApiError(String code, String message, String path, Instant timestamp) {

    public static ApiError of(ErrorCode code, String message, String path) {
        return new ApiError(code.getCode(), message, path, Instant.now());
    }
}
