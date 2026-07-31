package com.turkey.quick.member.service;

import com.turkey.quick.member.domain.VerificationPurpose;
import java.time.LocalDateTime;

/**
 * 서비스 내부 결과. 인증번호(code)는 로그에 남기지 않고,
 * 컨트롤러가 local 프로파일에서만 응답에 실을지 판단하는 데 쓴다.
 */
public record PhoneVerificationResult(
        String phoneNumber,
        VerificationPurpose purpose,
        String code,
        LocalDateTime expiresAt
) {
}
