package com.turkey.quick.order.dto;

/**
 * 라이더가 지금 수행 중인 배송의 식별자와 상태만 담은 투영(#449).
 *
 * <p><b>record 가 아니라 인터페이스인 이유</b>: 이 값을 채우는 조회
 * ({@code DeliveryOrderRepository.findInProgressByActiveRiderId})가 <b>네이티브 쿼리</b>라
 * JPQL 생성자 표현식({@code select new ...})을 쓸 수 없다. 스프링 데이터의 인터페이스 기반
 * 투영은 네이티브 쿼리에서도 동작하므로 그쪽을 따른다. 같은 이유로 {@link TrackableDelivery}
 * (JPQL 조회용 record)를 재사용하지 않는다 — 그건 생성자 표현식으로 채워진다.
 *
 * <p><b>상태를 {@code String} 으로 받는 이유</b>: 네이티브 쿼리의 VARCHAR 를 enum 으로 자동
 * 변환해 주는지는 스프링 데이터 버전·설정에 따라 달라진다. 변환 지점을 호출자
 * ({@code RiderLocationService})에 두어 명시적으로 만들고, 그 동치는 통합 테스트가 고정한다.
 *
 * <p>컬럼 별칭({@code AS orderId}, {@code AS status})이 이 인터페이스의 게터 이름과 맞아야
 * 한다 — 네이티브 투영은 ResultSet 의 컬럼 라벨로 프로퍼티를 찾기 때문이다.
 */
public interface InProgressDelivery {

    Long getOrderId();

    String getStatus();
}
