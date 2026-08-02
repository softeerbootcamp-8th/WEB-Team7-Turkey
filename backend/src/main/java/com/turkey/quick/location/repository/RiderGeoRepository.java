package com.turkey.quick.location.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 라이더 최신 위치 GEO 조회·반영. key: {@code riders:geo}, member: riderId(docs/03-erd.md).
 *
 * <p>쓰기는 배차 후보 등록/제거(#83)만 다룬다 — GEO 좌표 자체가 "지금 배차 후보로 검색되는가"를
 * 뜻하므로, AVAILABLE 이 아니거나 유효한 위치가 없으면 값을 남기지 않고 바로 지운다(호출부가
 * remove 를 호출). 호출부는 위치가 없는 경우(Optional.empty())를 항상 정상 흐름으로 처리해야 한다.
 */
@Repository
public class RiderGeoRepository {

    private static final String KEY = "riders:geo";

    private final StringRedisTemplate redisTemplate;

    public RiderGeoRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<Point> findPosition(Long riderId) {
        List<Point> positions = redisTemplate.opsForGeo().position(KEY, riderId.toString());
        if (positions == null || positions.isEmpty() || positions.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(positions.get(0));
    }

    /** GEOADD — 같은 member 로 다시 호출하면 좌표를 덮어쓴다(등록과 갱신이 같은 연산). */
    public void registerOrUpdate(Long riderId, BigDecimal latitude, BigDecimal longitude) {
        redisTemplate.opsForGeo().add(KEY, new Point(longitude.doubleValue(), latitude.doubleValue()),
                riderId.toString());
    }

    /**
     * 배차 후보에서 제외한다. GEO 는 ZSET 이 백엔드라 좌표 전용 삭제 명령이 따로 없으므로
     * ZREM({@code opsForZSet().remove})으로 지운다 — member 가 없어도 오류 없이 0을 돌려주는
     * 멱등 연산이다.
     */
    public void remove(Long riderId) {
        redisTemplate.opsForZSet().remove(KEY, riderId.toString());
    }
}
