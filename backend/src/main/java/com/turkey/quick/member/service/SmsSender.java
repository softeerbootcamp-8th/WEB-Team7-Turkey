package com.turkey.quick.member.service;

/** 외부 문자 발송 서비스 포트. MVP에서는 모의 구현(LoggingSmsSender)만 있다. */
public interface SmsSender {

    /** 발송 실패 시 SmsSendFailedException을 던진다. */
    void sendVerificationCode(String phoneNumber, String code);
}
