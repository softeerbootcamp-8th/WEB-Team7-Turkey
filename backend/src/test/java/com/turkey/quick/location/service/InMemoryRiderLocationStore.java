package com.turkey.quick.location.service;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * {@code compute} 는 ConcurrentHashMap 이 해당 키에 대해 원자적으로 실행하므로, Lua 스크립트와
     * 같은 의미론이 된다 — 비교와 쓰기 사이에 다른 스레드가 끼어들지 못한다.
     *
     * <p>다만 <b>흉내내는 것은 의미론뿐이다.</b> 인스턴스가 여러 대일 때 Redis 서버 한 곳에서
     * 직렬화되는 실제 원자성은 이 구현으로 검증되지 않는다(단위 테스트에는 스레드가 한 JVM 안에만
     * 있으니 당연하다). 그 검증은 {@code RedisRiderLocationStoreIntegrationTest} 가 실제 Redis 로 한다.
     */
    @Override
    public boolean saveIfNewer(Long riderId, RiderLocationSnapshot location) {
        // 반환값을 인자와 비교해 판정하지 않는다 — 같은 인스턴스를 두 번 넘기면 "쓰지 않았는데
        // 쓴 것"으로 보인다. 동시성 때문이 아니라 람다 밖으로 값을 꺼내기 위한 홀더다
        // (compute 의 함수는 키 잠금 안에서 정확히 한 번 실행된다).
        AtomicBoolean written = new AtomicBoolean();
        locations.compute(riderId, (id, previous) -> {
            if (previous != null && !location.measuredAt().isAfter(previous.measuredAt())) {
                return previous;
            }
            written.set(true);
            return location;
        });
        return written.get();
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
