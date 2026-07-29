package com.turkey.quick.location.service;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트 전용 인메모리 대체 구현. 로컬 Redis 없이 서비스·통합·E2E 테스트를 돌리기 위한 것으로,
 * InMemorySessionStore·InMemoryVerificationCodeStore와 같은 이유다. TTL은 실제로 만료시키지 않고
 * 값 존재만 흉내낸다 — 만료 동작 자체를 검증해야 하면 실제 Redis가 필요하다.
 *
 * 스프링 빈이 아니다(@Component 없음). 테스트가 @TestConfiguration 안에서 @Primary 빈으로 직접
 * 등록해 RedisRiderLocationStore를 덮는다(RiderSessionE2ETest.FakeInfraConfig와 같은 방식).
 */
public class InMemoryRiderLocationStore implements RiderLocationStore {

    private final ConcurrentHashMap<Long, RiderLocationSnapshot> locations = new ConcurrentHashMap<>();

    @Override
    public void save(Long riderId, RiderLocationSnapshot location) {
        locations.put(riderId, location);
    }

    @Override
    public Optional<RiderLocationSnapshot> find(Long riderId) {
        return Optional.ofNullable(locations.get(riderId));
    }

    /**
     * TTL을 흉내만 내므로 갱신할 것이 없다. 값이 있는지만 돌려준다 — 호출자가 보는 계약은
     * "갱신할 값이 실제로 있었는지"이고 그건 정확히 재현된다.
     */
    @Override
    public boolean refreshTtl(Long riderId) {
        return locations.containsKey(riderId);
    }

    /** 테스트가 저장 여부를 직접 들여다볼 때 쓴다(#235에서 "저장되지 않았음"을 단언하기 위해). */
    public boolean isEmpty() {
        return locations.isEmpty();
    }

    public void clear() {
        locations.clear();
    }
}
