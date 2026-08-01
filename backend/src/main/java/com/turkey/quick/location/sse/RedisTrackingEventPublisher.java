package com.turkey.quick.location.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turkey.quick.location.dto.RiderLocationSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.TrackableDelivery;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 라이더의 진행 중 배송을 찾아 그 주문 채널로 위치를 발행한다.
 *
 * <p><b>이 클래스가 위치 갱신 경로에 MySQL 의존을 새로 만든다.</b> {@code RiderLocationService} 는
 * "DB 를 읽지 않는다"가 명시적 설계였는데(그 javadoc 참고) 채널 키를 주문으로 고른 결과다.
 * 그 대가를 받아들인 근거:
 * <ul>
 *   <li>상태 조건이 붙은 조회가 <b>안전장치</b>다. 배송이 끝나면 결과가 비어 발행이 멈추고
 *       스트림이 조용해진다. 라이더를 키로 잡으면 완료 후 그 라이더의 다음 배송 경로가 이전
 *       고객에게 흘러 개인정보가 샌다.</li>
 *   <li>조회 자체를 <b>피할 수 없다.</b> {@code rider_location_history.order_id} 가 NOT NULL(V15)
 *       이라 위치 이력 저장(#102)도 같은 경로에서 주문을 풀어야 한다.</li>
 *   <li>비용은 유니크 인덱스 한 번이고, BUSY 라이더당 5초에 1회다.</li>
 * </ul>
 *
 * <p><b>트랜잭션 커밋 후 발행이 구조적으로 성립한다.</b> 리포지토리 호출이 각자 트랜잭션을 열고
 * 끝내며, 발행은 그 밖에서 일어난다. {@code @TransactionalEventListener(AFTER_COMMIT)} 를 쓰지
 * 않은 이유가 중요하다 — 활성 트랜잭션이 없으면 그 리스너는 <b>조용히 호출되지 않고</b>,
 * 그런데 테스트가 {@code TransactionTemplate} 안에서 서비스를 부르면 테스트만 통과하는 거짓
 * 통과가 된다. 나중에 {@code RiderLocationService} 에 {@code @Transactional} 을 붙이게 되면
 * 그때는 발행 시점을 다시 봐야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTrackingEventPublisher implements TrackingEventPublisher {

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public boolean publish(Long riderId, RiderLocationSnapshot location) {
        // 어떤 실패도 위치 갱신 POST 를 실패시키지 않는다. 조회(MySQL)·직렬화·발행(Redis)이
        // 각각 던질 수 있어 한 번에 감싼다.
        try {
            Optional<TrackableDelivery> delivery = deliveryOrderRepository
                    .findInProgressByRiderId(riderId, OrderStatus.trackableStatuses());
            if (delivery.isEmpty()) {
                // 배차 전이거나 배송이 끝난 라이더다. AVAILABLE 라이더의 30초 주기 위치가 대부분
                // 여기로 온다 — 정상 경로라 로그를 남기지 않는다.
                return false;
            }
            return send(delivery.get().orderId(), location);
        } catch (JsonProcessingException | RuntimeException e) {
            // 검사 예외(직렬화)와 런타임 예외(MySQL·Redis)를 함께 잡는다 — 처리 방식이 같다.
            log.warn("event=SSE_PUBLISH_FAILED riderId={} reason={}",
                    riderId, e.getClass().getSimpleName(), e);
            return false;
        }
    }

    /**
     * <p><b>{@code PUBLISH} 의 수신자 수를 쓰지 않는다.</b> 처음에는 그 값으로 "구독 인스턴스가
     * 있었는가"를 답하려 했는데, <b>모든 인스턴스가 패턴({@code tracking:order:*})으로 구독하므로
     * 수신자 수가 항상 1 이상</b>이다(자기 자신이 포함된다). 즉 그 값은 "앱이 떠 있는가"밖에
     * 알려주지 않는다 — 실제로 테스트가 그걸 잡았다.
     *
     * <p>그래서 발행 성공 자체를 돌려준다. 그 값이 답하는 질문은 "이 위치가 팬아웃 채널로
     * 나갔는가"이고, <b>고객이 보고 있는지는 알려주지 않는다.</b> 그걸 알려면 연결 집계
     * (ZSET)를 한 번 더 읽어야 하는데, 위치 갱신 핫패스에 Redis 왕복을 하나 더 얹을 값이
     * 있는지는 확인되지 않았다 — 필요해지면 그때 판단한다.
     *
     * @return 발행했으면 true
     */
    private boolean send(Long orderId, RiderLocationSnapshot location) throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(location);
        redisTemplate.convertAndSend(TrackingChannel.of(orderId), json);
        return true;
    }
}
