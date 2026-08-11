package com.turkey.quick.rider.service;

import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 로그인(이슈 #49). {@code CustomerLoginService}(#26)와 처리 흐름·실패 응답 정책이
 * 동일하고, 역할이 RIDER라는 것과 응답에 현재 운행 상태(RiderProfile.operatingStatus)를
 * 함께 반환한다는 점만 다르다. 로그인만으로 운행 상태를 바꾸지 않는다(이슈 비고) — 조회만 한다.
 */
@Service
@RequiredArgsConstructor
public class RiderLoginService {

    private static final String LOGIN_FAILURE_MESSAGE = "아이디 또는 비밀번호가 일치하지 않습니다.";
    // 최초 TTL. 인증 요청마다 인터셉터가 같은 값으로 다시 건다(슬라이딩, #439).
    private static final Duration SESSION_TTL = SessionStore.DEFAULT_TTL;

    private final MemberRepository memberRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final SessionStore sessionStore;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public RiderLoginResult login(String loginId, String password) {
        Member member = memberRepository.findByLoginId(loginId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE));

        if (member.getRole() != MemberRole.RIDER) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }
        if (!passwordEncoder.matches(password, member.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }
        if (!member.isActive()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }

        // 라이더 회원가입(#48)이 Member 저장과 한 트랜잭션으로 RiderProfile을 만들기 때문에
        // 활성 라이더 계정에 프로필이 없는 경우는 없다 — 존재를 별도로 검증하지 않는다.
        RiderProfile profile = riderProfileRepository.findById(member.getId()).orElseThrow();

        String sessionId = generateSessionId();
        sessionStore.create(sessionId, member.getId(), member.getRole().name(), SESSION_TTL);

        return new RiderLoginResult(
                sessionId, SESSION_TTL, member.getId(), member.getLoginId(), member.getName(),
                profile.getOperatingStatus());
    }

    private String generateSessionId() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
