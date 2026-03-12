package com.opencode.cui.skill.config;

import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.service.ProtocolException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 * 统一捕获 Controller 层抛出的异常，返回标准化 ApiResponse 格式。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理协议层自定义异常。
     */
    @ExceptionHandler(ProtocolException.class)
    public ResponseEntity<ApiResponse<?>> handleProtocolException(ProtocolException e) {
        log.warn("Protocol exception: code={}, message={}", e.getCode(), e.getMessage());
        int httpStatus = mapProtocolCodeToHttpStatus(e.getCode());
        return ResponseEntity.status(httpStatus)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /**
     * 处理参数校验异常。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, e.getMessage()));
    }

    /**
     * 兜底：处理所有未捕获的异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGenericException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "Internal server error"));
    }

    /**
     * 将 ProtocolException 的 code 映射为 HTTP 状态码。
     */
    private int mapProtocolCodeToHttpStatus(int code) {
        if (code >= 400 && code < 600) {
            return code;
        }
        // 非标准 code 默认返回 400
        return 400;
    }
}
