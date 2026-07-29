package com.turkey.quick.location.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인메모리 대체가 RiderLocationStore 계약을 실제로 지키는지 확인한다. 이 대체가 #235·#236 의
 * 통합·E2E 를 지탱하므로, 여기서 계약이 틀어지면 그 테스트들이 잘못된 전제 위에서 통과한다.
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

    @Test
    @DisplayName("저장한 위치를 조회할 수 있다")
    void findsSavedLocation() {
        RiderLocationSnapshot location = snapshot("37.4979", 0);

        store.save(RIDER_ID, location);

        assertThat(store.find(RIDER_ID)).contains(location);
    }

    @Test
    @DisplayName("저장된 적 없는 라이더는 빈 결과다")
    void returnsEmptyForUnknownRider() {
        assertThat(store.find(RIDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("다시 저장하면 최신 위치로 덮어쓴다")
    void overwritesWithLatestLocation() {
        store.save(RIDER_ID, snapshot("37.4979", 0));
        RiderLocationSnapshot latest = snapshot("37.5010", 1);

        store.save(RIDER_ID, latest);

        assertThat(store.find(RIDER_ID)).contains(latest);
    }

    @Test
    @DisplayName("라이더별로 위치가 섞이지 않는다")
    void keepsLocationsPerRider() {
        RiderLocationSnapshot first = snapshot("37.4979", 0);
        RiderLocationSnapshot second = snapshot("37.5010", 0);

        store.save(RIDER_ID, first);
        store.save(8L, second);

        assertThat(store.find(RIDER_ID)).contains(first);
        assertThat(store.find(8L)).contains(second);
    }
}
