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
    public boolean isInCooldown(VerificationPurpose purpose, String phoneNumber) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey(purpose, phoneNumber)));
    }

    @Override
    public void save(VerificationPurpose purpose, String phoneNumber, String code, Duration codeTtl, Duration cooldownTtl) {
        redisTemplate.opsForValue().set(codeKey(purpose, phoneNumber), code, codeTtl);
        redisTemplate.opsForValue().set(cooldownKey(purpose, phoneNumber), "1", cooldownTtl);
    }

    private String codeKey(VerificationPurpose purpose, String phoneNumber) {
        return CODE_KEY_FORMAT.formatted(purpose, phoneNumber);
    }

    private String cooldownKey(VerificationPurpose purpose, String phoneNumber) {
        return COOLDOWN_KEY_FORMAT.formatted(purpose, phoneNumber);
    }
}
