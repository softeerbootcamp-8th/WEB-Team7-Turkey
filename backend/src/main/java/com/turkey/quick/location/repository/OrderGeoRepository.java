package com.turkey.quick.location.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 주문 GEO 조회·반영. key: {@code order:geo}, member: orderId
 *
 * <p>쓰기는 배차 후보 등록/제거(#83)만 다룬다 — GEO 좌표 자체가 "지금 배차 후보로 검색되는가"를
 * 뜻하므로, AVAILABLE 이 아니거나 유효한 위치가 없으면 값을 남기지 않고 바로 지운다(호출부가
 * remove 를 호출). 호출부는 위치가 없는 경우(Optional.empty())를 항상 정상 흐름으로 처리해야 한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OrderGeoRepository {

    private static final String KEY = "order:geo";

    private final StringRedisTemplate redisTemplate;

    /** GEOADD — 같은 member 로 다시 호출하면 좌표를 덮어쓴다(등록과 갱신이 같은 연산). */
    public void registerOrUpdate(Long orderId, BigDecimal latitude, BigDecimal longitude) {
        redisTemplate.opsForGeo().add(KEY, new Point(longitude.doubleValue(), latitude.doubleValue()),
                orderId.toString());
    }

    /**
     * 주문이 배차 전에 취소되거나, 주문 상태가 WAITING이 아닌 경우.
     * ZREM({@code opsForZSet().remove})으로 지운다 — member 가 없어도 오류 없이 0을 돌려주는
     * 멱등 연산이다.
     */
    public void remove(Long orderId) {
        redisTemplate.opsForZSet().remove(KEY, orderId.toString());
    }

    public Optional<Point> findPosition(Long orderId) {
        List<Point> positions = redisTemplate.opsForGeo().position(KEY, orderId.toString());
        if (positions == null || positions.isEmpty() || positions.getFirst() == null) {
            return Optional.empty();
        }
        return Optional.of(positions.getFirst());
    }
}
