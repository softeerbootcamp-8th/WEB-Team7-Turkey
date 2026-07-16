package com.turkey.quick.common.response;

/**
 * 공통 API 응답 포맷. 모든 컨트롤러 응답을 이 형태로 감싸 성공/실패를 일관되게 표현한다.
 */
public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
