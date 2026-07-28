package com.turkey.quick.rider.config;

import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 라이더 전용 API에 세션 인증 인터셉터를 배선한다({@code CustomerWebMvcConfig}, #27과 동일한
 * 이유). 지금은 이 이슈(#50)가 만드는 /api/rider/session 하나만 등록한다 — 로그인·회원가입은
 * 인증이 필요 없는 공개 API다.
 */
@Configuration
@RequiredArgsConstructor
public class RiderWebMvcConfig implements WebMvcConfigurer {

    private final SessionStore sessionStore;
    private final MemberRepository memberRepository;
    private final RiderProfileRepository riderProfileRepository;

    @Value("${session.cookie.secure:true}")
    private boolean cookieSecure;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RiderSessionInterceptor(sessionStore, memberRepository, riderProfileRepository, cookieSecure))
                .addPathPatterns("/api/rider/session");
    }
}
