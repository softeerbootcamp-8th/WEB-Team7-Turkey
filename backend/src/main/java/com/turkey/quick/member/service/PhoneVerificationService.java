package com.turkey.quick.member.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.VerificationPurpose;
import com.turkey.quick.member.repository.MemberRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);

    private static final int CODE_LENGTH = 6;
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final MemberRepository memberRepository;
    private final VerificationCodeStore verificationCodeStore;
    private final SmsSender smsSender;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public PhoneVerificationResult request(String rawPhoneNumber, VerificationPurpose purpose) {
        String phoneNumber = rawPhoneNumber.replace("-", "");

        if (purpose == VerificationPurpose.SIGNUP && memberRepository.existsByPhoneNumber(phoneNumber)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 가입된 휴대전화 번호입니다.");
        }

        if (verificationCodeStore.isInCooldown(purpose, phoneNumber)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요.");
        }

        String code = generateCode();
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(CODE_TTL);

        verificationCodeStore.save(purpose, phoneNumber, code, CODE_TTL, RESEND_COOLDOWN);

        try {
            smsSender.sendVerificationCode(phoneNumber, code);
        } catch (SmsSendFailedException e) {
            log.warn("인증번호 발송 실패 purpose={}", purpose, e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "인증번호 발송에 실패했습니다.");
        }

        return new PhoneVerificationResult(phoneNumber, purpose, code, expiresAt);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        int value = secureRandom.nextInt(bound);
        return String.format("%0" + CODE_LENGTH + "d", value);
    }
}
