package com.turkey.quick.common.logging;

/** 요청 단위 로그를 엮는 MDC 키. Filter+MDC와 이후 추가될 인증 필터가 공유하는 계약이다. */
public final class RequestId {

    public static final String REQUEST_ID = "requestId";
    public static final String MEMBER_ID = "memberId";

    private RequestId() {
    }
}
