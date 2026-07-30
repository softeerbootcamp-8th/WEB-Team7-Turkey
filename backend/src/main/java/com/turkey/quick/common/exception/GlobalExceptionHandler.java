package com.turkey.quick.common.exception;

import com.turkey.quick.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        return fail(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return fail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // @RequestParam/@PathVariable에 붙은 Bean Validation(@Validated 컨트롤러) 실패는
    // MethodArgumentNotValidException이 아니라 이 예외로 온다.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        return fail(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return fail(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * <b>Content-Type 을 JSON 으로 못 박는 이유</b>(#77 에서 드러남): 응답에 Content-Type 이
     * 지정돼 있지 않으면 스프링이 요청의 {@code Accept} 헤더로 컨텐트 협상을 하고, 맞는 컨버터가
     * 없으면 {@code HttpMediaTypeNotAcceptableException}(406)을 던진다.
     *
     * <p>SSE 구독은 브라우저 {@code EventSource} 가 {@code Accept: text/event-stream} <b>만</b>
     * 보내므로, 명시하지 않으면 401·409·429 가 전부 <b>406 으로 바뀌어 상태코드와
     * {@code ApiResponse} 본문을 둘 다 잃는다.</b> 프론트가 의존하는 응답 계약이 깨지는 지점이다.
     *
     * <p>기존 엔드포인트는 모두 JSON 응답이라 이 변경이 동작을 바꾸지 않는다. 실제로 협상이
     * 필요한(여러 표현을 내보내는) 엔드포인트가 생기면 그때 이 지점을 다시 본다.
     */
    private static ResponseEntity<ApiResponse<Void>> fail(HttpStatusCode status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(message));
    }
}
