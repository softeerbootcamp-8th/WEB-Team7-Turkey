package com.turkey.quick.customer.config;

import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import com.turkey.quick.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 고객 전용 API에 세션 인증 인터셉터를 배선한다. 로그인·회원가입은 인증이 필요 없는 공개 API라
 * 여기 포함하면 안 된다. 인증이 필요한 고객 API를 추가할 때마다 그 경로를 addPathPatterns에 더한다.
 *
 * <p><b>등록을 빠뜨리면 그 API는 인증 없이 열린 채로 배포된다</b>(Spring Security 미사용,
 * CLAUDE.md 「확정된 결정」). 그래서 새 경로를 추가할 때는 "쿠키 없이 호출하면 401"을 E2E 회귀
 * 테스트로 함께 둔다.
 *
 * <p><b>{@code /api/customer/deliveries/**} 로 넓히지 않는 이유</b>: 현재 유일하게 구현된
 * {@code POST /api/customer/deliveries/quote}(요금 견적)가 인증 없이 열려 있는데, 한 번에 넓히면
 * 그 API가 조용히 401이 되어 프론트 견적 화면이 깨진다. 인증을 거는 것 자체는 맞는 방향이지만
 * 이 이슈가 임의로 바꿀 범위가 아니다 — 그 API를 구현하는 이슈에서 함께 등록한다.
 * 그래서 {@code /api/customer/deliveries} 아래는 <b>정확 경로로 한 건씩</b> 등록한다(#37).
 */
@Configuration
@RequiredArgsConstructor
public class CustomerWebMvcConfig implements WebMvcConfigurer {

    private final SessionStore sessionStore;
    private final MemberRepository memberRepository;

    @Value("${session.cookie.secure:true}")
    private boolean cookieSecure;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CustomerSessionInterceptor(sessionStore, memberRepository, cookieSecure))
                .addPathPatterns(
                        "/api/customer/session",                                 // #27
                        "/api/customer/deliveries",                              // #37 (정확 경로)
                        "/api/customer/deliveries/*",                            // #46 (상세 조회)
                        "/api/customer/deliveries/*/tracking",                   // #79
                        "/api/customer/deliveries/*/tracking/stream",            // #77
                        "/api/customer/deliveries/*/location",                   // #311(폴링 arm)
                        "/api/customer/points/**"
                );
    }
}
