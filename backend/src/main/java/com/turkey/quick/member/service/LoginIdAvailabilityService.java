package com.turkey.quick.member.service;

import com.turkey.quick.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginIdAvailabilityService {

    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public LoginIdAvailabilityResult check(String loginId) {
        if (memberRepository.existsByLoginId(loginId)) {
            return LoginIdAvailabilityResult.ofUnavailable("이미 사용 중인 아이디입니다.");
        }
        return LoginIdAvailabilityResult.ofAvailable();
    }
}
