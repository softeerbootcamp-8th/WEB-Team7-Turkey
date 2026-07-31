package com.turkey.quick.member.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.VerificationPurpose;
import com.turkey.quick.member.repository.MemberRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
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
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration VERIFIED_TOKEN_TTL = Duration.ofMinutes(10);

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

        // 쿨다운 선점을 원자적으로 해서, 동시 요청 중 하나만 통과시킨다(코드 생성 전에 승자를 가린다).
        if (!verificationCodeStore.reserveCooldown(purpose, phoneNumber, RESEND_COOLDOWN)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요.");
        }

        String code = generateCode();
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(CODE_TTL);

        verificationCodeStore.saveCode(purpose, phoneNumber, code, CODE_TTL);

        try {
            smsSender.sendVerificationCode(phoneNumber, code);
        } catch (SmsSendFailedException e) {
            log.warn("인증번호 발송 실패 purpose={}", purpose, e);
            // 전달되지 않은 코드와 쿨다운을 모두 지워, 재시도가 429로 막히지 않게 한다.
            verificationCodeStore.release(purpose, phoneNumber);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "인증번호 발송에 실패했습니다.");
        }

        return new PhoneVerificationResult(phoneNumber, purpose, code, expiresAt);
    }

    @Transactional(readOnly = true)
    public PhoneVerificationConfirmResult confirm(String rawPhoneNumber, VerificationPurpose purpose, String inputCode) {
        String phoneNumber = rawPhoneNumber.replace("-", "");

        String savedCode = verificationCodeStore.getCode(purpose, phoneNumber);
        if (savedCode == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "인증 요청 이력이 없거나 만료되었습니다. 인증번호를 다시 요청해 주세요.");
        }

        long attempts = verificationCodeStore.incrementAttempts(purpose, phoneNumber, CODE_TTL);
        if (attempts > MAX_ATTEMPTS) {
            verificationCodeStore.clearVerification(purpose, phoneNumber);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "인증 시도 횟수를 초과했습니다. 인증번호를 다시 요청해 주세요.");
        }

        if (!savedCode.equals(inputCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다.");
        }

        // 검증에 성공했으니 코드는 재사용할 수 없게 지우고, 일회성 인증 완료 토큰을 새로 발급한다.
        verificationCodeStore.clearVerification(purpose, phoneNumber);
        String token = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(VERIFIED_TOKEN_TTL);
        verificationCodeStore.saveVerifiedToken(token, purpose, phoneNumber, VERIFIED_TOKEN_TTL);

        return new PhoneVerificationConfirmResult(token, expiresAt);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        int value = secureRandom.nextInt(bound);
        return String.format("%0" + CODE_LENGTH + "d", value);
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
