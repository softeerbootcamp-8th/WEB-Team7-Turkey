package com.turkey.quick.member.service;

import com.turkey.quick.member.domain.VerificationPurpose;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisVerificationCodeStore implements VerificationCodeStore {

    private static final String CODE_KEY_FORMAT = "phone-verification:code:%s:%s";
    private static final String COOLDOWN_KEY_FORMAT = "phone-verification:cooldown:%s:%s";
    private static final String ATTEMPTS_KEY_FORMAT = "phone-verification:attempts:%s:%s";
    private static final String VERIFIED_KEY_FORMAT = "phone-verification:verified:%s";

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

    @Override
    public String getCode(VerificationPurpose purpose, String phoneNumber) {
        return redisTemplate.opsForValue().get(codeKey(purpose, phoneNumber));
    }

    @Override
    public long incrementAttempts(VerificationPurpose purpose, String phoneNumber, Duration ttl) {
        String key = attemptsKey(purpose, phoneNumber);
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return attempts == null ? 0L : attempts;
    }

    @Override
    public void clearVerification(VerificationPurpose purpose, String phoneNumber) {
        redisTemplate.delete(codeKey(purpose, phoneNumber));
        redisTemplate.delete(attemptsKey(purpose, phoneNumber));
    }

    @Override
    public void saveVerifiedToken(String token, VerificationPurpose purpose, String phoneNumber, Duration ttl) {
        redisTemplate.opsForValue().set(verifiedKey(token), purpose + ":" + phoneNumber, ttl);
    }

    @Override
    public String consumeVerifiedToken(String token) {
        // GETDEL: 조회와 삭제를 원자적으로 묶어, 같은 토큰으로 동시에 들어온 요청이 둘 다 값을 받는 것을 막는다.
        return redisTemplate.opsForValue().getAndDelete(verifiedKey(token));
    }

    private String codeKey(VerificationPurpose purpose, String phoneNumber) {
        return CODE_KEY_FORMAT.formatted(purpose, phoneNumber);
    }

    private String cooldownKey(VerificationPurpose purpose, String phoneNumber) {
        return COOLDOWN_KEY_FORMAT.formatted(purpose, phoneNumber);
    }

    private String attemptsKey(VerificationPurpose purpose, String phoneNumber) {
        return ATTEMPTS_KEY_FORMAT.formatted(purpose, phoneNumber);
    }

    private String verifiedKey(String token) {
        return VERIFIED_KEY_FORMAT.formatted(token);
    }
}
