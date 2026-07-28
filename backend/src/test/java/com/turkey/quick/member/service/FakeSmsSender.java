package com.turkey.quick.member.service;

public class FakeSmsSender implements SmsSender {

    private boolean shouldFail = false;
    private String lastPhoneNumber;
    private String lastCode;

    @Override
    public void sendVerificationCode(String phoneNumber, String code) {
        if (shouldFail) {
            shouldFail = false; // 다음 호출(재시도)은 성공하도록 1회성으로 되돌린다.
            throw new SmsSendFailedException("모의 발송 실패");
        }
        this.lastPhoneNumber = phoneNumber;
        this.lastCode = code;
    }

    public void failNext() {
        this.shouldFail = true;
    }

    public String lastPhoneNumber() {
        return lastPhoneNumber;
    }

    public String lastCode() {
        return lastCode;
    }
}
