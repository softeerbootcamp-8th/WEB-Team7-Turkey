package com.turkey.quick.location.service;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.util.Optional;

/**
 * 라이더 최신 위치의 저장소. 운영에서는 Redis(TTL)를 쓰고, 통합·E2E 테스트에서는 로컬 Redis 없이
 * 돌 수 있도록 인메모리 구현으로 대체한다({@code SessionStore}·{@code VerificationCodeStore}와
 * 같은 이유).
 *
 * 최신 위치의 정본은 Redis 다(docs/02-domain-policy.md §7). MySQL
 * ({@code rider_location_history})에는 장기 보관이 필요한 이력만 선별 저장하며(#102) 최신 위치
 * 조회 경로로 쓰지 않는다.
 *
 * TTL 을 파라미터로 받지 않는 이유: 세션 TTL(2시간)은 로그인 정책이라 호출자가 정할 일이지만
 * (그래서 {@code SessionStore.create} 는 받는다), 최신 위치의 유효 시간은 이 저장소의 인프라
 * 성질이다. 호출자가 매번 넘기면 서비스마다 다른 값이 들어갈 통로가 생긴다.
 *
 * {@code delete} 가 없는 것은 의도적이다. 운행 종료 시 최신 위치를 즉시 지우는 것이 맞지만
 * 그 API(운행 상태 변경)가 아직 구현되지 않아 부를 곳이 없다. 그 이슈에서 함께 추가한다.
 * 그때까지는 TTL 이 대신 정리하므로 <b>운행 종료 후 최대 TTL 만큼 배차 후보에 남는 틈</b>이
 * 있다 — 알고 있는 한계다.
 */
public interface RiderLocationStore {

    /** 라이더의 최신 위치를 저장한다. 기존 값이 있으면 덮어쓰고 TTL 을 다시 건다. */
    void save(Long riderId, RiderLocationSnapshot location);

    /**
     * 최신 위치가 있으면 반환한다. 저장된 적이 없거나 TTL 로 만료됐으면 빈 결과다.
     * 저장된 값이 손상돼 해석할 수 없는 경우도 빈 결과로 취급한다 — 잘못된 좌표를 배차·추적에
     * 흘리는 것보다 "위치 없음"이 낫다.
     */
    Optional<RiderLocationSnapshot> find(Long riderId);
}
