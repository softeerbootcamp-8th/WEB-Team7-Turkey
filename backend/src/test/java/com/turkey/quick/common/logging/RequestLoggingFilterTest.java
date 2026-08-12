package com.turkey.quick.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    @DisplayName("응답 헤더에 requestId를 담는다")
    void shouldIncludeRequestIdInResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isNotBlank();
    }

    @Test
    @DisplayName("요청마다 requestId가 다르다")
    void shouldCreateDifferentRequestIdForEachRequest() throws Exception {
        FilterChain chain = (req, res) -> {
        };

        MockHttpServletResponse response1 = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/health"), response1, chain);

        MockHttpServletResponse response2 = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/health"), response2, chain);

        assertThat(response1.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER))
                .isNotEqualTo(response2.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("정상 처리 후 MDC가 정리된다")
    void shouldClearMdcAfterSuccessfulRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
        };

        filter.doFilter(request, response, chain);

        assertThat(MDC.get(RequestId.REQUEST_ID)).isNull();
    }

    @Test
    @DisplayName("체인에서 예외가 발생해도 MDC가 정리된다")
    void shouldClearMdcWhenFilterChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(RequestId.REQUEST_ID)).isNull();
    }
}
