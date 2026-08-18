package com.turkey.quick.order.dto;

import com.turkey.quick.order.domain.ItemType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 라이더 콜 목록(#55/#367)이 실제로 쓰는 8개 필드만 담은 투영(discussion #549, #559).
 *
 * <p>{@code DeliveryOrder} 는 29개 컬럼(발신·수신인 연락처, 6개 단계별 타임스탬프, 취소사유 등)을
 * 매핑하지만 콜 목록 응답({@code RiderDeliveryRequestSummaryResponse})이 읽는 건 이 8개뿐이다.
 * 엔터티로 로드하면 나머지 21개 컬럼도 매번 추출되고, 엔터티 인스턴스화·영속성 컨텍스트 등록
 * 비용까지 붙는다 — 이 조회는 {@code @Transactional(readOnly = true)}이고 값을 바꾸지 않으므로
 * (grep으로 {@code save()}/{@code persist()} 호출 없음을 확인) 그 비용을 낼 이유가 없다.
 *
 * <p><b>{@code pickup}/{@code destination}(둘 다 {@code @Embeddable Address})을 평탄화해서
 * 노출하는 이유</b>: 이 인터페이스를 채우는 조회 중 하나({@code
 * DeliveryOrderRepository#findWaitingOrdersWithinBoundingBox})가 네이티브 쿼리라, 결과 매핑이
 * {@code ResultSet} 컬럼 라벨 ↔ 게터 이름의 완전히 평평한 1:1 매칭만 지원한다 — {@code
 * getPickup(): Address} 처럼 여러 컬럼을 하나의 중첩 객체로 묶어 반환하도록 지시할 방법이 없다.
 * 두 조회 메서드가 같은 타입을 반환해야 호출부({@code RiderDeliveryRequestService})가 갈라지지
 * 않으므로, 파생 쿼리({@code findByStatus}) 쪽도 같은 평탄화 규약을 따른다.
 *
 * <p>컬럼 별칭({@code AS pickupRoadAddress} 등)이 이 인터페이스의 게터 이름과 정확히 맞아야 한다
 * — 네이티브 투영은 {@code ResultSet} 의 컬럼 라벨로 프로퍼티를 찾기 때문이다({@link
 * InProgressDelivery} 와 같은 규약).
 */
public interface WaitingDeliverySummary {

    Long getId();

    ItemType getItemType();

    String getPickupRoadAddress();

    BigDecimal getPickupLatitude();

    BigDecimal getPickupLongitude();

    String getDestinationRoadAddress();

    Integer getStraightDistanceMeters();

    LocalDateTime getRequestedAt();
}
