package com.turkey.quick.customer.config;

import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import com.turkey.quick.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 고객 전용 API에 세션 인증 인터셉터를 배선한다. 지금은 이 이슈(#27)가 만드는
 * /api/customer/session 하나만 등록한다 — 로그인·회원가입은 인증이 필요 없는 공개 API라
 * 여기 포함하면 안 된다. 이후 인증이 필요한 고객 API가 추가되면 그 경로를 addPathPatterns에
 * 더한다.
 */
@Configuration
@RequiredArgsConstructor
public class CustomerWebMvcConfig implements WebMvcConfigurer {

    private final SessionStore sessionStore;
    private final MemberRepository memberRepository;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CustomerSessionInterceptor(sessionStore, memberRepository))
                .addPathPatterns("/api/customer/session");
    }
}
