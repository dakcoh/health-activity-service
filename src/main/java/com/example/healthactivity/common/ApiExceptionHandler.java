package com.example.healthactivity.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail authentication(AuthenticationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException exception) {
        // 사전 중복 검사 이후의 동시 가입도 DB 유일 제약으로 차단한다.
        Throwable cause = exception;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().toLowerCase(java.util.Locale.ROOT)
                    .contains("uk_members_email")) {
                return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "이미 등록된 이메일입니다.");
            }
            cause = cause.getCause();
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "데이터를 저장하지 못했습니다.");
    }
}
