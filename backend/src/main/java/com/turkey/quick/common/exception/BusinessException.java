package com.turkey.quick.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 도메인 규칙 위반이지만 400(형식 오류)으로 뭉뚱그릴 수 없는 경우(409, 429 등)에 던진다.
 * HTTP 상태 코드를 예외가 직접 들고 있어 GlobalExceptionHandler가 그대로 응답에 반영한다.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
