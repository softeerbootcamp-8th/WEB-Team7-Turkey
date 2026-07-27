package com.turkey.quick.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void 응답_헤더에_requestId를_담는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isNotBlank();
    }

    @Test
    void 요청마다_requestId가_다르다() throws Exception {
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
    void 정상_처리_후_MDC가_정리된다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
        };

        filter.doFilter(request, response, chain);

        assertThat(MDC.get(RequestId.REQUEST_ID)).isNull();
    }

    @Test
    void 체인에서_예외가_발생해도_MDC가_정리된다() {
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
