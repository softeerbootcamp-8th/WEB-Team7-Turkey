package com.turkey.quick.member.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MVP 모의 발송기. 실제 문자 발송 대신 로그만 남긴다.
 * 인증번호는 로그 금지 대상(docs/logging-guidelines.md §8)이라 절대 남기지 않는다.
 */
@Component
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void sendVerificationCode(String phoneNumber, String code) {
        log.info("[MOCK-SMS] 인증번호 발송 phoneNumber={}", mask(phoneNumber));
    }

    private String mask(String phoneNumber) {
        if (phoneNumber.length() <= 4) {
            return "****";
        }
        return "*".repeat(phoneNumber.length() - 4) + phoneNumber.substring(phoneNumber.length() - 4);
    }
}
