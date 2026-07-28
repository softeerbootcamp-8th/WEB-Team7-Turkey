package com.turkey.quick.member.service;

import com.turkey.quick.member.domain.VerificationPurpose;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisVerificationCodeStore implements VerificationCodeStore {

    private static final String CODE_KEY_FORMAT = "phone-verification:code:%s:%s";
    private static final String COOLDOWN_KEY_FORMAT = "phone-verification:cooldown:%s:%s";

    private final StringRedisTemplate redisTemplate;

    public RedisVerificationCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean reserveCooldown(VerificationPurpose purpose, String phoneNumber, Duration cooldownTtl) {
        // SET NX EX: 키가 없을 때만 원자적으로 생성한다. 동시 요청 중 하나만 true를 받는다.
        Boolean reserved = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey(purpose, phoneNumber), "1", cooldownTtl);
        return Boolean.TRUE.equals(reserved);
    }

    @Override
    public void saveCode(VerificationPurpose purpose, String phoneNumber, String code, Duration codeTtl) {
        redisTemplate.opsForValue().set(codeKey(purpose, phoneNumber), code, codeTtl);
    }

    @Override
    public void release(VerificationPurpose purpose, String phoneNumber) {
        redisTemplate.delete(codeKey(purpose, phoneNumber));
        redisTemplate.delete(cooldownKey(purpose, phoneNumber));
    }

    private String codeKey(VerificationPurpose purpose, String phoneNumber) {
        return CODE_KEY_FORMAT.formatted(purpose, phoneNumber);
    }

    private String cooldownKey(VerificationPurpose purpose, String phoneNumber) {
        return COOLDOWN_KEY_FORMAT.formatted(purpose, phoneNumber);
    }
}
