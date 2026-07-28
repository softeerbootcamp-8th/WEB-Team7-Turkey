package com.turkey.quick.member.service;

/** SmsSender 구현체가 발송 실패를 알릴 때 던진다. HTTP 상태 변환은 서비스 계층이 맡는다. */
public class SmsSendFailedException extends RuntimeException {

    public SmsSendFailedException(String message) {
        super(message);
    }
}
