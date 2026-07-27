package com.turkey.quick.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 단위 공통 로그(Filter+MDC)를 담당한다. requestId 발급, 응답 헤더 반환,
 * 완료 로그 기록, MDC 정리까지 이 필터 하나에서 처리한다.
 *
 * 도메인 이벤트 로그(주문 생성, 배차 성공/실패 등)는 이 필터의 책임이 아니며,
 * 각 서비스 코드에서 직접 남긴다. 예외 자체의 로깅도 여기서 하지 않는다 —
 * 동일 예외를 여러 계층에서 중복 기록하지 않기 위해 전역 예외 처리기 책임으로 남긴다.
 */
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(RequestId.REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long startTimeMillis = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startTimeMillis;
            log.info("event=REQUEST_COMPLETED requestId={} method={} uri={} status={} durationMs={}",
                    requestId, request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.clear();
        }
    }
}
