package com.enterprise.investment;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException error) {
        String message = error.getReason() == null ? "请求未完成" : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(new ApiError(message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError validation(MethodArgumentNotValidException error) {
        var field = error.getBindingResult().getFieldError();
        return new ApiError(field == null ? "请检查提交的数据" : "字段 " + field.getField() + " 的值无效");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HandlerMethodValidationException.class,
            MissingServletRequestPartException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError badRequest() {
        return new ApiError("请检查请求参数和数据格式");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError conflict() {
        return new ApiError("记录已存在、仍有关联数据，或违反数据约束");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    ApiError uploadTooLarge() {
        return new ApiError("文件大小不能超过 5 MB");
    }

    record ApiError(String message) {
    }
}
