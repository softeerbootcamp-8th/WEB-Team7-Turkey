package com.turkey.quick.member.service;

import com.turkey.quick.member.domain.VerificationPurpose;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트 전용 인메모리 대체 구현. 로컬 Redis 없이 서비스·통합·E2E 테스트를 돌리기 위한 것으로,
 * MySQL 통합 테스트를 H2로 대체하는 것과 같은 이유다. TTL은 실제로 만료시키지 않고 키 존재만 흉내낸다.
 */
public class InMemoryVerificationCodeStore implements VerificationCodeStore {

    private final Set<String> cooldownKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> codes = new ConcurrentHashMap<>();

    @Override
    public boolean isInCooldown(VerificationPurpose purpose, String phoneNumber) {
        return cooldownKeys.contains(key(purpose, phoneNumber));
    }

    @Override
    public void save(VerificationPurpose purpose, String phoneNumber, String code, Duration codeTtl, Duration cooldownTtl) {
        codes.put(key(purpose, phoneNumber), code);
        cooldownKeys.add(key(purpose, phoneNumber));
    }

    public String savedCode(VerificationPurpose purpose, String phoneNumber) {
        return codes.get(key(purpose, phoneNumber));
    }

    private String key(VerificationPurpose purpose, String phoneNumber) {
        return purpose + ":" + phoneNumber;
    }
}
