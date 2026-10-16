package com.learnhub.assistant.api;

import com.learnhub.assistant.runtime.AssistantExecutionManager;
import com.learnhub.common.api.ApiResponse;
import com.learnhub.common.exception.BusinessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.learnhub.assistant.api")
public class AssistantExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException error) { return AssistantExecutionManager.failure(error); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> invalid(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("ASSISTANT_INPUT_INVALID", "请检查课程编号和问题长度"));
    }
}
