package com.devmind.common.exception;

/**
 * 业务异常：携带错误码，由全局异常处理器转成统一 JSON。
 */
public class DevMindException extends RuntimeException {

    private final ErrorCode errorCode;

    public DevMindException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public DevMindException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DevMindException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
