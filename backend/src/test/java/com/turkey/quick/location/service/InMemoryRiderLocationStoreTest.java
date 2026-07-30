package com.turkey.quick.location.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 인메모리 대체가 RiderLocationStore 계약을 실제로 지키는지 확인한다. 이 대체가 #235·#236 의
 * 단위 테스트를 지탱하므로, 여기서 계약이 틀어지면 그 테스트들이 잘못된 전제 위에서 통과한다.
 *
 * <p>조건부 갱신 의미론(#250)은 여기와 {@link RedisRiderLocationStoreIntegrationTest} 양쪽에서
 * 같은 케이스로 검증한다. 같은 것을 두 층에서 보는 것으로 보이지만, 두 구현이 서로 다른 기술로
 * 같은 계약을 지켜야 하는 것이 요점이다 — 한쪽만 검증하면 서비스 단위 테스트가 실제 Redis 와
 * 다르게 동작하는 대체 위에서 통과할 수 있다.
 */
@DisplayName("InMemoryRiderLocationStore")
class InMemoryRiderLocationStoreTest {

    private static final Long RIDER_ID = 7L;

    private final InMemoryRiderLocationStore store = new InMemoryRiderLocationStore();

    private static RiderLocationSnapshot snapshot(String latitude, int minute) {
        return new RiderLocationSnapshot(
                new BigDecimal(latitude),
                new BigDecimal("127.0276"),
                LocalDateTime.of(2026, 7, 29, 12, minute),
                null);
    }

    @Nested
    @DisplayName("조건부 갱신")
    class SaveIfNewer {

        @Test
        @DisplayName("저장된 값이 없으면 저장한다")
        void savesWhenAbsent() {
            RiderLocationSnapshot location = snapshot("37.4979", 0);

            assertThat(store.saveIfNewer(RIDER_ID, location)).isTrue();
            assertThat(store.find(RIDER_ID)).contains(location);
        }

        @Test
        @DisplayName("더 최신이면 덮어쓴다")
        void overwritesWithNewerLocation() {
            store.saveIfNewer(RIDER_ID, snapshot("37.4979", 0));
            RiderLocationSnapshot latest = snapshot("37.5010", 1);

            assertThat(store.saveIfNewer(RIDER_ID, latest)).isTrue();
            assertThat(store.find(RIDER_ID)).contains(latest);
        }

        @Test
        @DisplayName("과거 측정 시각이면 저장하지 않고 기존 값을 남긴다")
        void keepsLatestWhenOlder() {
            RiderLocationSnapshot latest = snapshot("37.5010", 1);
            store.saveIfNewer(RIDER_ID, latest);

            assertThat(store.saveIfNewer(RIDER_ID, snapshot("37.4979", 0))).isFalse();
            assertThat(store.find(RIDER_ID)).contains(latest);
        }

        @Test
        @DisplayName("같은 측정 시각이면 저장하지 않는다")
        void rejectsSameTimestamp() {
            // 재전송이라 값은 같은데 쓰기만 늘어난다. "최신일 때만"의 경계값이다.
            RiderLocationSnapshot first = snapshot("37.4979", 0);
            store.saveIfNewer(RIDER_ID, first);

            assertThat(store.saveIfNewer(RIDER_ID, snapshot("37.5010", 0))).isFalse();
            assertThat(store.find(RIDER_ID)).contains(first);
        }

        @Test
        @DisplayName("같은 인스턴스를 다시 넘겨도 저장하지 않았다고 답한다")
        void rejectsSameInstanceOnResend() {
            // 반환값을 인자와 비교해 판정하면 여기서 true 가 나온다. 서비스가 그 값으로
            // NON_MONOTONIC 을 판정하므로, 대체 구현이 틀리면 단위 테스트가 조용히 갈린다.
            RiderLocationSnapshot location = snapshot("37.4979", 0);
            store.saveIfNewer(RIDER_ID, location);

            assertThat(store.saveIfNewer(RIDER_ID, location)).isFalse();
        }

        @Test
        @DisplayName("라이더별로 위치가 섞이지 않는다")
        void keepsLocationsPerRider() {
            RiderLocationSnapshot first = snapshot("37.4979", 0);
            RiderLocationSnapshot second = snapshot("37.5010", 0);

            store.saveIfNewer(RIDER_ID, first);
            store.saveIfNewer(8L, second);

            assertThat(store.find(RIDER_ID)).contains(first);
            assertThat(store.find(8L)).contains(second);
        }
    }

    @Nested
    @DisplayName("조회")
    class Find {

        @Test
        @DisplayName("저장된 적 없는 라이더는 빈 결과다")
        void returnsEmptyForUnknownRider() {
            assertThat(store.find(RIDER_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("TTL 갱신")
    class RefreshTtl {

        @Test
        @DisplayName("저장된 값이 있으면 true 다")
        void reportsPresentValue() {
            store.saveIfNewer(RIDER_ID, snapshot("37.4979", 0));

            assertThat(store.refreshTtl(RIDER_ID)).isTrue();
        }

        @Test
        @DisplayName("저장된 값이 없으면 false 다")
        void reportsAbsentValue() {
            // 호출자가 보는 계약은 "갱신할 값이 실제로 있었는지"다. TTL 을 흉내만 내는 이 구현도
            // 그 계약은 정확히 재현한다.
            assertThat(store.refreshTtl(RIDER_ID)).isFalse();
        }
    }
}
