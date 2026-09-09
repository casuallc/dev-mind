package com.devmind.session.controller;

import com.devmind.common.exception.ApiError;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 统一异常 → JSON。业务异常用自身状态码，参数校验 400，兜底 500。
 * devmind.error.include-stacktrace=true（仅本地排错）时响应体附带异常堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Value("${devmind.error.include-stacktrace:false}")
    private boolean includeStackTrace;

    @ExceptionHandler(DevMindException.class)
    public ResponseEntity<ApiError> handleBiz(DevMindException e, HttpServletRequest req) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ApiError.of(code, e.getMessage(), req.getRequestURI(), stackTraceOf(e)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getDefaultMessage()).orElse(ErrorCode.BAD_REQUEST.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.BAD_REQUEST, msg, req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception e, HttpServletRequest req) {
        log.error("未处理异常: {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL.getStatus())
                .body(ApiError.of(ErrorCode.INTERNAL, e.getMessage(), req.getRequestURI(), stackTraceOf(e)));
    }

    /** 开关关闭时返回 null（ApiError 序列化时省略该字段） */
    private String stackTraceOf(Throwable e) {
        if (!includeStackTrace) {
            return null;
        }
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
