package com.turkey.quick.location.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turkey.quick.location.dto.LocationPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 라이더의 새 위치를 그 배송의 Pub/Sub 채널로 발행한다(#317).
 *
 * <p>이 발행이 팬아웃의 전부다 — 어느 인스턴스에 SSE 연결이 있든 {@link TrackingSubscriber} 가
 * 받아서 자기 연결로만 보낸다. 발행자는 구독자가 있는지 알지 않는다.
 *
 * <p><b>어떤 예외도 밖으로 내지 않는다.</b> 이 호출은 라이더 위치 갱신 POST 경로에 있고, 여기서
 * 실패가 올라가면 위치 갱신 자체가 실패한다 — 그러면 Redis 장애가 고객 추적을 넘어 배차 후보
 * 갱신(#83)까지 같이 죽인다. 전달은 at-most-once 이고 유실은 다음 위치(BUSY 5초 주기)가 복구한다.
 *
 * <p>옛 구현({@code RedisTrackingEventPublisher})이 채널 키를 만들기 위해 감내했던 MySQL 조회
 * ({@code findInProgressByRiderId})는 없다. #290 이후 {@code deliveryId} 가 요청 본문으로 오기
 * 때문이다 — 그 대가로 <b>라이더가 그 배송에 실제로 배정됐는지는 검증되지 않는다</b>(#291 에서 남은
 * 구멍, 별건).
 *
 * <p><b>{@code PUBLISH} 의 수신자 수는 쓰지 않는다.</b> 모든 인스턴스가 패턴
 * ({@code tracking:order:*})으로 구독하므로 자기 자신이 포함돼 항상 1 이상이고, 즉 "앱이 떠 있는가"
 * 밖에 알려주지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackingPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(Long deliveryId, LocationPayload location) {
        try {
            // 직렬화를 여기서 한 번만 한다. 구독자는 파싱하지 않고 이 문자열을 SSE data: 로 그대로
            // 흘리므로, 페이로드 계약이 이 한 줄에만 존재한다.
            redisTemplate.convertAndSend(TrackingChannel.of(deliveryId),
                    objectMapper.writeValueAsString(location));
        } catch (JsonProcessingException | RuntimeException e) {
            // 검사 예외(직렬화)와 런타임 예외(Redis)를 함께 잡는다 — 처리 방식이 같다.
            log.warn("event=SSE_PUBLISH_FAILED deliveryId={} reason={}",
                    deliveryId, e.getClass().getSimpleName(), e);
        }
    }
}
