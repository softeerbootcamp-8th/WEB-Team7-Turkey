package com.turkey.quick.member.service;

import com.turkey.quick.member.domain.VerificationPurpose;
import java.time.Duration;

/**
 * 휴대전화 인증번호의 휘발성 저장소. 운영에서는 Redis(TTL)를 쓰고,
 * 통합·E2E 테스트에서는 로컬 Redis 없이 돌 수 있도록 인메모리 구현으로 대체한다
 * (MySQL 통합 테스트를 H2로 대체하는 것과 같은 이유).
 */
public interface VerificationCodeStore {

    /** 쿨다운 키가 아직 살아 있으면 재전송을 막아야 한다. */
    boolean isInCooldown(VerificationPurpose purpose, String phoneNumber);

    /** 인증번호(codeTtl 만료)와 재전송 쿨다운(cooldownTtl 만료)을 함께 기록한다. */
    void save(VerificationPurpose purpose, String phoneNumber, String code, Duration codeTtl, Duration cooldownTtl);
}
