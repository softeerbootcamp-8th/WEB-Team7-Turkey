package com.turkey.quick.location.sse;

import com.turkey.quick.location.dto.RiderLocationSnapshot;

/**
 * 라이더의 새 위치를 그 배송을 구독 중인 고객들에게 팬아웃한다(#78 흐름 ⑤).
 *
 * <p>인터페이스로 분리한 이유는 두 가지다. {@code RiderLocationService} 가 Redis·주문 리포지토리를
 * 직접 알지 않게 하는 것, 그리고 그 서비스의 단위 테스트가 스프링 없이 돌 수 있게 하는 것이다.
 *
 * <p><b>구현은 절대 예외를 던지지 않는다.</b> 이 호출은 라이더의 위치 갱신 POST 경로에 있고,
 * 여기서 실패가 올라가면 <b>위치 갱신 자체가 실패한다.</b> 그러면 Redis·MySQL 장애가 고객 추적을
 * 넘어 배차 후보 검색(#83)까지 같이 죽인다. 실패는 {@code false} 로 돌려주고, 유실은 감내한다 —
 * 전달은 at-most-once 이고 다음 이벤트(BUSY 5초 주기)와 재연결 스냅샷이 복구한다.
 */
public interface TrackingEventPublisher {

    /**
     * @param riderId  위치를 보낸 라이더(= member_id)
     * @param location 발행할 위치. 이미 검증·필터를 통과한 값이다
     * @return <b>팬아웃 채널로 발행했는지.</b> 고객에게 도달했다는 뜻이 <b>아니다</b> — 그건 알 수
     *         없고 알 필요도 없다(at-most-once). 라이더가 진행 중인 배송이 없거나(배차 전·완료 후)
     *         발행에 실패하면 false
     */
    boolean publish(Long riderId, RiderLocationSnapshot location);
}
