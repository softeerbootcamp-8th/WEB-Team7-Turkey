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
 * 이유). 로그인·회원가입은 인증이 필요 없는 공개 API라 등록하지 않는다.
 *
 * <p><b>인증이 선언적으로 자동 적용되지 않는다.</b> Spring Security 를 쓰지 않으므로, 보호할
 * 라이더 API 를 추가할 때마다 그 경로를 아래 {@code addPathPatterns} 에 직접 더해야 한다.
 * 빠뜨리면 그 API 는 인증 없이 열린 채로 배포된다({@code CLAUDE.md} 「확정된 결정」). 그래서
 * 각 API 의 E2E 테스트에 "쿠키 없이 호출하면 401" 케이스를 두어 누락을 회귀로 잡는다.
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
                .addPathPatterns("/api/rider/session",
                                 "/api/rider/requests",
                                 "/api/rider/requests/**",
                                 "/api/rider/location"   
                                );
    }
}
