package com.turkey.quick.common.config;

import com.turkey.quick.common.logging.RequestLoggingFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * RequestLoggingFilter를 필터 체인의 맨 앞(HIGHEST_PRECEDENCE)에 등록한다.
 * 이후 추가될 인증 필터보다 항상 먼저 실행되어야, 인증에 실패해 컨트롤러까지
 * 도달하지 못하는 요청도 완료 로그에서 빠지지 않는다.
 */
@Configuration
public class RequestLoggingFilterConfig {

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestLoggingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
