package com.turkey.quick.member.service;

import com.turkey.quick.member.domain.VerificationPurpose;
import java.time.Duration;

/**
 * 휴대전화 인증번호의 휘발성 저장소. 운영에서는 Redis(TTL)를 쓰고,
 * 통합·E2E 테스트에서는 로컬 Redis 없이 돌 수 있도록 인메모리 구현으로 대체한다
 * (MySQL 통합 테스트를 H2로 대체하는 것과 같은 이유).
 */
public interface VerificationCodeStore {

    /**
     * 쿨다운 키를 원자적으로 선점한다(Redis SET NX와 동등). 동시 요청 중 하나만 true를 받는다.
     * 이미 쿨다운 중이면 false — 호출자는 코드를 생성·저장하지 않고 곧바로 거부해야 한다.
     */
    boolean reserveCooldown(VerificationPurpose purpose, String phoneNumber, Duration cooldownTtl);

    /** 쿨다운 선점에 성공한 요청만 호출한다. 인증번호를 codeTtl 만료로 저장한다. */
    void saveCode(VerificationPurpose purpose, String phoneNumber, String code, Duration codeTtl);

    /** 문자 발송 실패 시 롤백용. 저장된 코드와 쿨다운을 모두 지워 즉시 재시도할 수 있게 한다. */
    void release(VerificationPurpose purpose, String phoneNumber);
}
