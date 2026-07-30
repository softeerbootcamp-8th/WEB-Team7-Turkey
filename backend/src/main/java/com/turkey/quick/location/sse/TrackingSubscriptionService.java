package com.turkey.quick.location.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.RiderLocationSnapshot;
import com.turkey.quick.location.service.RiderLocationStore;
import com.turkey.quick.order.dto.TrackableDelivery;
import com.turkey.quick.order.service.DeliveryTrackingAccessService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실시간 위치 구독 시작(#77 처리 흐름 ①~⑥).
 *
 * <p><b>검증 순서가 이 클래스의 핵심이다.</b> SSE 는 연결이 성립하면 이미 200 과
 * {@code text/event-stream} 헤더가 나가 버려 <b>그 뒤의 실패를 HTTP 로 표현할 방법이 없다.</b>
 * 그래서 실패할 수 있는 것을 전부 {@code SseEmitter} 반환 <i>전에</i> 끝낸다:
 *
 * <ol>
 *   <li>세션 — 인터셉터가 이미 처리했다(401). <b>경로를
 *       {@code CustomerWebMvcConfig.addPathPatterns} 에 등록하지 않으면 이 단계가 통째로
 *       빠진다</b>(Spring Security 미사용).</li>
 *   <li>소유권·상태 — {@link DeliveryTrackingAccessService}(404/409). 여기까지가 트랜잭션 안이다.</li>
 *   <li>연결 한도 — {@link TrackingConnectionLimiter}(429). Redis 장애도 여기서 드러난다(503).</li>
 *   <li>emitter 생성·등록 — <b>이 선을 넘으면 되돌릴 수 없다.</b></li>
 *   <li>{@code init} 전송.</li>
 * </ol>
 *
 * <p>3에서 실패하면 아직 아무것도 등록하지 않았으므로 되돌릴 것이 없다 — 이 순서가 "부분 성공
 * 없음"을 만든다.
 *
 * <p><b>트랜잭션이 없다.</b> {@code @Transactional} 을 여기 붙이면 수 분간 열려 있는 응답이
 * 트랜잭션 경계 안에서 반환되어, 나중에 DB 커넥션을 그만큼 붙잡는 코드가 끼어들 여지가 생긴다.
 * DB 를 만지는 것은 2단계뿐이고 그 경계는 그 서비스가 갖는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingSubscriptionService {

    private static final String LIMIT_EXCEEDED_MESSAGE = "이 배송에 연결할 수 있는 수를 초과했습니다.";
    private static final String UNAVAILABLE_MESSAGE = "실시간 추적을 시작할 수 없습니다. 잠시 후 다시 시도해 주세요.";

    private final DeliveryTrackingAccessService accessService;
    private final RiderLocationStore riderLocationStore;
    private final TrackingConnectionLimiter connectionLimiter;
    private final TrackingEmitterRegistry registry;
    private final ObjectMapper objectMapper;

    /**
     * @param deliveryId 구독할 배송요청
     * @param customerId 세션에서 얻은 고객 식별자
     * @return 열린 SSE 연결. 컨트롤러가 그대로 반환하면 스프링이 async 응답으로 바꾼다
     * @throws BusinessException 404(없음·타인 것) / 409(추적 불가 상태) / 429(연결 한도) /
     *                           503(Redis 장애)
     */
    public SseEmitter subscribe(Long deliveryId, Long customerId) {
        TrackableDelivery delivery = accessService.authorizeTracking(deliveryId, customerId);

        // 초기 이벤트를 먼저 만들어 둔다. 위치 조회(Redis)와 직렬화가 실패할 수 있는데, 슬롯을
        // 잡은 뒤에 실패하면 그 슬롯과 레지스트리 항목이 새기 때문이다. 실패 가능한 것을 전부
        // 되돌릴 수 있는 구간에 모아 두는 것이 "부분 성공 없음"의 실제 구현이다.
        String initJson = buildInitEvent(delivery);

        String emitterId = UUID.randomUUID().toString();
        acquireSlot(deliveryId, emitterId);

        // 여기부터는 실패해도 HTTP 로 알릴 수 없다.
        TrackingConnection connection = new TrackingConnection(
                emitterId, deliveryId, new SseEmitter(TrackingStreamPolicy.EMITTER_TIMEOUT.toMillis()));
        registry.add(connection);
        registerCleanup(connection);

        log.info("event=SSE_CONNECTED orderId={} emitterId={} riderId={}",
                deliveryId, emitterId, delivery.riderId());
        if (!connection.sendInit(initJson)) {
            // 사실상 도달하지 않는다 — 컨트롤러가 반환하기 전이라 emitter 가 아직 응답에 붙지
            // 않았고, 그 상태의 send 는 내부 버퍼에 쌓인다. 그래도 확인하는 이유는 그게 스프링
            // 내부 구현 세부이기 때문이다(바뀌면 조용히 빈 스트림이 된다).
            cleanUp(connection, "INIT_FAILED");
            connection.complete();
        }
        return connection.emitter();
    }

    /**
     * Redis 장애를 503 으로 바꿔 준다. 잡지 않으면 {@code DataAccessException} 이 그대로 올라가고,
     * {@code GlobalExceptionHandler} 에 포괄 {@code Exception} 핸들러가 없어
     * <b>{@code ApiResponse} 형태가 아닌 500</b> 이 나간다.
     */
    private void acquireSlot(Long deliveryId, String emitterId) {
        boolean acquired;
        try {
            acquired = connectionLimiter.tryAcquire(deliveryId, emitterId, Instant.now());
        } catch (DataAccessException e) {
            log.error("event=SSE_SUBSCRIBE_REJECTED orderId={} reason={}", deliveryId, "STORE_UNAVAILABLE", e);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        if (!acquired) {
            // 이 로그가 카운터 누수의 유일한 운영 신호다 — 같은 주문에서 계속 나오고 값이 줄지
            // 않으면 죽은 기록이 자리를 차지하고 있다는 뜻이다.
            log.warn("event=SSE_SUBSCRIBE_REJECTED orderId={} emitterId={} reason={} limit={}",
                    deliveryId, emitterId, "LIMIT", TrackingStreamPolicy.MAX_CONNECTIONS_PER_ORDER);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, LIMIT_EXCEEDED_MESSAGE);
        }
    }

    /**
     * 세 콜백 모두 같은 정리 함수를 부른다. {@code onTimeout}/{@code onError} 뒤에는
     * {@code onCompletion} 이 <b>이어서 온다</b> — 즉 정리는 두 번 이상 호출되고, 그래서 레지스트리
     * 제거와 한도 해제가 모두 멱등이어야 한다.
     *
     * <p>{@code onTimeout} 에서 {@code complete()} 를 부르는 이유: 타임아웃만으로는 응답이 닫히지
     * 않아 컨테이너가 그 상태로 남겨 둔다.
     */
    private void registerCleanup(TrackingConnection connection) {
        SseEmitter emitter = connection.emitter();
        emitter.onCompletion(() -> cleanUp(connection, "COMPLETED"));
        emitter.onTimeout(() -> {
            cleanUp(connection, "TIMEOUT");
            connection.complete();
        });
        emitter.onError(throwable -> cleanUp(connection, "ERROR"));
    }

    private void cleanUp(TrackingConnection connection, String reason) {
        registry.remove(connection.orderId(), connection.emitterId());
        releaseSlot(connection);
        // MDC 에 기대지 않는다 — 이 콜백은 요청 스레드가 아니라 컨테이너 스레드에서 돈다.
        log.info("event=SSE_DISCONNECTED orderId={} emitterId={} reason={}",
                connection.orderId(), connection.emitterId(), reason);
    }

    /**
     * <b>인터럽트 플래그를 잠시 걷어내고 해제한다.</b> Lettuce 는 응답을 {@code future.get()} 으로
     * 기다리므로, 플래그가 서 있는 스레드에서 부르면 <b>즉시</b> {@code InterruptedException} 이 나고
     * {@code RedisSystemException("Redis command interrupted")} 으로 번역된다.
     *
     * <p><b>실측으로 확인한 발생 조건은 애플리케이션 종료다.</b> 종료 시 Tomcat 이 아직 열려 있는
     * SSE 요청을 강제 중단하면서 그 스레드를 인터럽트하고, 그와 동시에 Lettuce 커넥션도 정리된다.
     * 이 경로에서는 플래그를 걷어내도 실패할 수 있다 — 인터럽트가 {@code await} <i>중에</i>
     * 다시 도착하기 때문이다. 그래도 플래그를 걷어내는 이유는 그 밖의 경로(한 번만 인터럽트되는
     * 경우)에서는 이것만으로 해제가 성공하기 때문이다.
     *
     * <p>그 실패를 감내할 수 있는 이유가 ZSET 을 쓴 근거와 같다: 남은 기록은 stale 임계(45초)가
     * 걷어낸다. 정수 카운터였다면 종료마다 영구 누수가 됐다.
     *
     * <p><b>주의</b>: 평범한 클라이언트 끊김에서는 이 콜백이 아예 <b>즉시 돌지 않는다.</b> SSE 는
     * 쓰기를 시도할 때만 끊김을 알 수 있어서, 조용한 스트림은 heartbeat 가 실패할 때까지(또는
     * emitter 타임아웃까지) 살아 있는 것으로 취급된다. 그래서 heartbeat 가 죽은 연결 탐지 장치를
     * 겸한다.
     *
     * <p>복원하는 이유: 인터럽트는 이 스레드의 것이 아니라 컨테이너가 요청을 중단하려는 신호다.
     * 삼키면 그 뒤의 중단 처리가 신호를 잃는다.
     */
    private void releaseSlot(TrackingConnection connection) {
        boolean interrupted = Thread.interrupted();
        try {
            connectionLimiter.release(connection.orderId(), connection.emitterId());
        } catch (DataAccessException e) {
            // Redis 가 실제로 죽은 경우다. 연결 정리는 계속해야 하고, 남은 기록은 stale 임계가
            // 걷어낸다 — 그게 정수 카운터가 아니라 ZSET 을 쓴 이유다.
            log.warn("event=SSE_RELEASE_FAILED orderId={} emitterId={}",
                    connection.orderId(), connection.emitterId(), e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 흐름 ④⑤⑥의 본문을 만든다. 상태는 이미 2단계에서 읽었고, 위치는 Redis 에서 읽는다 —
     * <b>없으면 상태만 보낸다</b>(이슈 예외 흐름: 배차 직후이거나 TTL 10분이 지난 경우).
     */
    private String buildInitEvent(TrackableDelivery delivery) {
        RiderLocationSnapshot location;
        try {
            location = riderLocationStore.find(delivery.riderId()).orElse(null);
        } catch (DataAccessException e) {
            // Redis 장애를 "위치 없음"으로 위장하지 않는다(RedisRiderLocationStore.find 와 같은
            // 판단). 위치 없이 스트림을 열면 고객은 "라이더가 아직 위치를 안 보냈다"로 오해한다.
            log.error("event=SSE_SUBSCRIBE_REJECTED orderId={} reason={}",
                    delivery.orderId(), "STORE_UNAVAILABLE", e);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        try {
            return objectMapper.writeValueAsString(
                    new TrackingInitPayload(delivery.orderId(), delivery.status(), location));
        } catch (JsonProcessingException e) {
            // 실제로는 도달하지 않는다(필드가 Long·enum·BigDecimal·LocalDateTime 뿐이다). 검사
            // 예외라 처리해야 하는데, IllegalStateException 으로 던지면 GlobalExceptionHandler 가
            // 잡지 않아(포괄 Exception 핸들러가 없다) ApiResponse 아닌 500 이 나간다.
            log.error("event=SSE_SUBSCRIBE_REJECTED orderId={} reason={}",
                    delivery.orderId(), "INIT_SERIALIZATION", e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, UNAVAILABLE_MESSAGE);
        }
    }
}
