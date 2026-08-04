package com.turkey.quick.location.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("라이더 최신 위치 GEO 조회·반영(#83)")
class RiderGeoRepositoryTest {

    private static final String KEY = "riders:geo";
    private static final Long RIDER_ID = 7L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private RiderGeoRepository repository() {
        return new RiderGeoRepository(redisTemplate);
    }

    @Test
    @DisplayName("registerOrUpdate는 위도·경도를 GEOADD로 등록한다")
    void registerOrUpdateAddsPoint() {
        given(redisTemplate.opsForGeo()).willReturn(geoOperations);

        repository().registerOrUpdate(RIDER_ID, new BigDecimal("37.5000000"), new BigDecimal("127.0000000"));

        verify(geoOperations).add(eq(KEY),
                eq(new Point(127.0, 37.5)), eq(RIDER_ID.toString()));
    }

    @Test
    @DisplayName("remove는 ZREM으로 후보에서 뺀다")
    void removeDeletesMember() {
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);

        repository().remove(RIDER_ID);

        verify(zSetOperations).remove(KEY, RIDER_ID.toString());
    }

    @Test
    @DisplayName("위치가 없으면 조회 결과는 비어 있다")
    void findPositionReturnsEmptyWhenMissing() {
        given(redisTemplate.opsForGeo()).willReturn(geoOperations);
        given(geoOperations.position(KEY, RIDER_ID.toString())).willReturn(List.of());

        Optional<Point> position = repository().findPosition(RIDER_ID);

        assertThat(position).isEmpty();
    }
}
