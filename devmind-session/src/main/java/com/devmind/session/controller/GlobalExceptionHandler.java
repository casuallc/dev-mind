package com.devmind.session.controller;

import com.devmind.common.exception.ApiError;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 统一异常 → JSON。业务异常用自身状态码，客户端错误（校验/类型/方法/路径）用对应 4xx，
 * 兜底 500。devmind.error.include-stacktrace=true（仅本地排错）时响应体附带异常堆栈。
 *
 * <p>框架异常必须逐个显式登记：本类的 {@code @ExceptionHandler(Exception.class)} 是个兜底
 * 捕获，不登记就会被它接住并统一报 500——于是「不存在的路径」「方法用错了」这类客户端
 * 错误全变成 DEV-500，状态码失去意义（E2E 断言 404 会假失败、前端也分不清谁的问题）。
 * 新增框架异常处理时同理，别把 4xx 交给兜底。</p>
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

    /** 路径不存在（无任何 handler 命中，含静态资源处理器兜底后的未命中）→ 404。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception e, HttpServletRequest req) {
        log.debug("路径不存在: {} {}", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getStatus())
                .body(ApiError.of(ErrorCode.NOT_FOUND,
                        "路径不存在: " + req.getMethod() + " " + req.getRequestURI(), req.getRequestURI()));
    }

    /** 路径存在但 HTTP 方法不对 → 405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                           HttpServletRequest req) {
        log.debug("请求方法不支持: {} {}", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiError.of(ErrorCode.METHOD_NOT_ALLOWED,
                        "请求方法不支持: " + req.getMethod() + " " + req.getRequestURI(), req.getRequestURI()));
    }

    /** 路径变量/查询参数类型不符（如 /documents/abc 要 Long）→ 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                       HttpServletRequest req) {
        String want = e.getRequiredType() == null ? "正确类型" : e.getRequiredType().getSimpleName();
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.BAD_REQUEST,
                        "参数 " + e.getName() + " 类型不正确，应为 " + want, req.getRequestURI()));
    }

    /** 请求体不可解析（JSON 语法错/类型不符/缺失）→ 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.BAD_REQUEST, "请求体格式错误，无法解析", req.getRequestURI()));
    }

    /** 必填查询参数缺失 → 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException e,
                                                       HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.BAD_REQUEST, "缺少必填参数: " + e.getParameterName(), req.getRequestURI()));
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
