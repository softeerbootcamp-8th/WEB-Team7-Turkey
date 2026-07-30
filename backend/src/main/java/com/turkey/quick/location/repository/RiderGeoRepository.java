package com.turkey.quick.location.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 라이더 최신 위치 GEO 조회(읽기 전용). key: {@code riders:geo}, member: riderId(docs/03-erd.md).
 *
 * 이 키에 라이더 위치를 쓰는 경로(RIDE-LOC-001)는 아직 별도 이슈로 진행 중이라, 실제 배포 환경에서는
 * 아직 위치가 없는 라이더가 많을 수 있다. 그래서 이 클래스는 쓰기를 다루지 않고 조회만 하며,
 * 호출부는 위치가 없는 경우(Optional.empty())를 항상 정상 흐름으로 처리해야 한다.
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
}
