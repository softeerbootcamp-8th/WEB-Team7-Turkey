package com.turkey.quick.customer.service;

import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 로그인(이슈 #26). 실패 사유(아이디 없음/역할 불일치/비밀번호 불일치/비활성 계정)를
 * 구분해 응답하지 않는다 — 이슈 비고에 "계정 존재 여부를 구체적으로 노출하지 않는다"고
 * 명시돼 있어, 모든 실패를 동일한 401 메시지로 응답한다.
 */
@Service
@RequiredArgsConstructor
public class CustomerLoginService {

    private static final String LOGIN_FAILURE_MESSAGE = "아이디 또는 비밀번호가 일치하지 않습니다.";

    // 최초 TTL 2시간(사람 확인, #26 계약). 인증을 통과한 요청마다 인터셉터가 같은 값으로 다시
    // 건다(슬라이딩, #439) — 값이 갈리면 안 되므로 SessionStore.DEFAULT_TTL 하나만 쓴다.
    private static final Duration SESSION_TTL = SessionStore.DEFAULT_TTL;

    private final MemberRepository memberRepository;
    private final SessionStore sessionStore;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public CustomerLoginResult login(String loginId, String password) {
        Member member = memberRepository.findByLoginId(loginId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE));

        if (member.getRole() != MemberRole.CUSTOMER) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }
        if (!passwordEncoder.matches(password, member.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }
        if (!member.isActive()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }

        String sessionId = generateSessionId();
        sessionStore.create(sessionId, member.getId(), member.getRole().name(), SESSION_TTL);

        return new CustomerLoginResult(sessionId, SESSION_TTL, member.getId(), member.getLoginId(), member.getName());
    }

    private String generateSessionId() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
