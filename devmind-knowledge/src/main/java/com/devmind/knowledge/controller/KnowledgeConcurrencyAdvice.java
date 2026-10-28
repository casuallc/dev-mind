package com.devmind.knowledge.controller;
import com.devmind.common.exception.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
@RestControllerAdvice(basePackages="com.devmind.knowledge.controller")
public class KnowledgeConcurrencyAdvice {
    @ExceptionHandler({org.springframework.dao.OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class,org.springframework.dao.PessimisticLockingFailureException.class})
    public ResponseEntity<ApiError> changed(jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.status(409).body(ApiError.of(ErrorCode.CONFLICT,"资料或策略已被修改，请刷新后重试",request.getRequestURI()));
    }
}
