package com.turkey.quick.location.sse;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import com.turkey.quick.order.domain.OrderStatus;

/**
 * 구독 직후 한 번 보내는 {@code init} 이벤트의 본문(#77 흐름 ④⑤⑥).
 *
 * <p>이것 없이는 첫 위치 이벤트가 올 때까지 지도를 그릴 수 없다. 정지한 라이더는 좌표가
 * {@code DUPLICATE} 로 판정돼 발행되지 않으므로 <b>그 대기가 무한할 수 있다.</b>
 *
 * <p><b>{@code DeliveryTrackingResponse}(#79 스냅샷 REST)와 공유하지 않는다.</b> 그쪽은 Orval 이
 * 생성하는 REST 계약이고 이쪽은 OpenAPI 에 노출되지 않는 스트림 내부 계약이다. 한 DTO 로 묶으면
 * 스트림 페이로드가 REST 계약 변경에 끌려다니고, 겹치는 필드는 {@code status} 하나뿐이다.
 * 화면 초기 렌더에 필요한 타임라인·라이더 연락처·요금은 그쪽 몫이다.
 *
 * <p><b>이 형식은 배포 호환성 표면이다.</b> 롤링 배포 중 구·신 버전이 공존하면 서로 다른 형식이
 * 같은 채널로 나갈 수 있다(Flyway 마이그레이션, Redis 값 형식에 이은 세 번째 표면).
 * <b>필드 추가만 허용하고 제거·의미 변경은 하지 않는다.</b>
 *
 * @param location 라이더의 마지막으로 알려진 위치. <b>null 일 수 있다</b> — 배차 직후라 아직
 *                 위치를 보내지 않았거나 Redis TTL(10분)이 지났으면 없다. 이슈의 예외 흐름이
 *                 "위치가 없으면 상태만 전송한다"고 정한 경우다
 */
public record TrackingInitPayload(
        Long deliveryId,
        OrderStatus status,
        RiderLocationSnapshot location
) {
}
