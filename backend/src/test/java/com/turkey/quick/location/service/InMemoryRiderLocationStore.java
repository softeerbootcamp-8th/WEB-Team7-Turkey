package com.turkey.quick.location.service;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>단위 테스트</b> 전용 인메모리 대체 구현. 스프링 컨텍스트 없이 서비스 로직만 검증할 때 쓴다
 * (InMemorySessionStore·InMemoryVerificationCodeStore와 같은 이유).
 *
 * 통합·E2E는 이 대체를 쓰지 않는다 — 실제 Docker Redis에 붙는다(2026-07-29 변경). 그러니
 * 스프링 빈으로 등록하지 않고, 테스트가 직접 {@code new} 로 만들어 서비스에 넣는다.
 *
 * TTL은 실제로 만료시키지 않고 값 존재만 흉내낸다. 만료 동작을 검증해야 하면 실제 Redis가 필요하다.
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
}
