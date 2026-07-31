package com.turkey.quick.common.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * 세션 쿠키 이름과 발급 규칙. 고객·라이더 로그인이 모두 같은 이름·속성을 쓰도록 여기 모아 둔다
 * (#27 로그인 상태 확인, #28 로그아웃도 같은 이름으로 쿠키를 읽거나 만료시켜야 한다).
 *
 * SameSite=Lax + Secure(프로파일별)를 쓰는 이유: 프론트(S3+CloudFront)와 API(EC2)를 CloudFront
 * 하나의 배포 안에서 경로로 묶어(/api/* → EC2 origin) 브라우저 기준 같은 origin으로 만들기로
 * 했다(#26 계약 확정, 사람 확인). 그래서 크로스사이트 쿠키가 필요 없고 Lax로 충분하다. 도메인
 * 구성이 이후 실제로 분리된 CloudFront 배포(다른 site)로 바뀌면 SameSite=None 재검토가 필요하다.
 */
public final class SessionCookie {

    public static final String NAME = "SESSION_ID";

    private SessionCookie() {
    }

    public static ResponseCookie of(String sessionId, Duration ttl, boolean secure) {
        return ResponseCookie.from(NAME, sessionId)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(ttl)
                .build();
    }

    /** 로그아웃 응답용 — 브라우저가 즉시 삭제하도록 maxAge=0으로 발급한다(#28). */
    public static ResponseCookie expired(boolean secure) {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    /** 요청 쿠키에서 세션 ID를 꺼낸다. 쿠키가 없거나 이 이름이 없으면 null. */
    public static String extractSessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
