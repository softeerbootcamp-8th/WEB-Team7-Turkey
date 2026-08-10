package com.turkey.quick.location.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.InProgressDelivery;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 라이더 위치 갱신. 요청은 좌표만 담고 있으므로 <b>이 서비스가 배송 식별자를 DB 로 직접 풀어낸다.</b>
 *
 * <p><b>위치 스트리밍·저장은 BUSY(배송 추적) 전용이다</b>(디스커션 #338, #342). AVAILABLE 라이더는
 * 배경 위치를 전송하지 않고 — 콜 목록을 검색하는 그 순간의 좌표를 검색 요청에 실어 보낸다 — 그래서
 * 이 엔드포인트는 BUSY 가 아니면 409 로 거부한다. BUSY 요청만이 최신 위치를 저장하고(고객 추적
 * 스냅샷용, {@link RiderLocationRepository}) 수행 중 배송 채널로 팬아웃 발행한다.
 *
 * <p>예전에는 AVAILABLE 라이더 좌표를 GEO({@code riders:geo})에 등록·갱신했지만, 배차 위치 검색을
 * 라이더가 아니라 주문 픽업지 인덱싱으로 뒤집으면서(#101 미구현) 라이더-측 GEO 사용처를 전부
 * 제거했다(#342). AVAILABLE 라이더에게는 <b>발행할 채널도 저장할 이유도 없다</b> — 배송을 맡지
 * 않은 라이더를 추적하는 고객은 존재할 수 없고, 그 좌표를 검색하는 곳도 없다(#101 미구현).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderLocationService {

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final RiderLocationRepository riderLocationRepository;
    private final TrackingPublisher trackingPublisher;

    /**
     * <p><b>{@code @Transactional} 을 붙이지 않았다.</b> 리포지토리 호출이 스스로 트랜잭션을 열고
     * 끝내며, Redis 쓰기와 팬아웃 발행은 그 밖에서 일어난다. 여기에 트랜잭션을 걸면 Redis 왕복과
     * Pub/Sub 발행이 DB 커넥션을 잡은 채로 진행된다.
     *
     * <p><b>BUSY 가 아니면 409 로 거부한다.</b> 부정형 검사(== BUSY 가 아니면 거부)를 쓰는 이유는
     * 나중에 상태가 하나 늘었을 때 <b>허용으로 조용히 새지 않고 거부가 기본값</b>이 되게 하기
     * 위해서다 — 위치를 저장·발행할 자격은 오직 BUSY 뿐이다.
     */
    public void update(AuthenticatedRider rider, LocationPayload location) {
        if (rider.operatingStatus() != OperatingStatus.BUSY) {
            throw new BusinessException(HttpStatus.CONFLICT, "배송 수행 중(BUSY)이 아닙니다.");
        }
        saveLatestLocation(rider, location);
        relayToTrackingCustomers(rider, location);
    }

    /**
     * 최신 위치 저장(#317, 고객 추적 스냅샷용).
     *
     * <p>Redis 갱신이 실패해도 이 요청 자체는 계속 성공해야 하므로 예외를 삼키고 로깅만 한다 —
     * 최신 위치는 다음 전송(BUSY 5초 주기)이 복구한다.
     */
    private void saveLatestLocation(AuthenticatedRider rider, LocationPayload location) {
        try {
            riderLocationRepository.saveIfNewer(rider.memberId(), location);
        } catch (RuntimeException e) {
            log.warn("event=RIDER_LOCATION_REDIS_SYNC_FAILED riderId={} operatingStatus={}",
                    rider.memberId(), rider.operatingStatus(), e);
        }
    }

    /**
     * 라이더가 수행 중인 배송을 찾아 그 채널로 발행한다.
     *
     * <p><b>5초 주기로 불리는 조회다</b>(BUSY 전송 주기, #81). 그래서 이력 전체를 훑는
     * {@code findInProgressByRiderId} 가 아니라 생성 컬럼의 유니크 인덱스를 그대로 타는
     * {@link DeliveryOrderRepository#findInProgressByActiveRiderId} 를 쓴다 — 주문 이력이 쌓여도
     * 비용이 늘지 않는다.
     *
     * <p><b>조회(5초마다 다시 조회하잖아)를 없애고 라이더→배송 매핑을 캐시하지 않는 이유</b>:
     * 5초마다 조회하는 비용을 내고 개인정보 유출(세션 안끝난 고객이 다른 실시간 정보 조회) 버그의
     * 가능성을 원천 차단한다.
     *
     * <p><b>조회 실패가 위치 갱신을 실패시키지 않는다.</b> 이 경로에 MySQL 의존이 있으므로,
     * DB 장애가 최신 위치 저장까지 같이 죽이지 않도록 예외를 삼킨다. 전달은
     * at-most-once 이고 다음 전송(5초)이 복구한다.
     *
     * <p>수행 중 배송이 없는 BUSY 라이더는 <b>정합성이 깨진 상태</b>다.
     * 이때 WARN 을 남긴다 — 배송 완료 처리와 라이더 상태 전이가 어긋났을 때 여기로 드러난다.
     */
    private void relayToTrackingCustomers(AuthenticatedRider rider, LocationPayload location) {
        try {
            Optional<InProgressDelivery> delivery = deliveryOrderRepository
                    .findInProgressByActiveRiderId(rider.memberId());
            if (delivery.isEmpty()) {
                log.warn("event=RIDER_LOCATION_RELAY_SKIPPED riderId={} reason={}",
                        rider.memberId(), "NO_IN_PROGRESS_DELIVERY");
                return;
            }
            // 네이티브 쿼리라 상태가 문자열로 온다(InProgressDelivery Javadoc). 여기서 한 번만
            // 변환하고, 값이 열거형에 없으면 그건 데이터 정합성 오류라 아래 catch 로 떨어진다.
            OrderStatus status = OrderStatus.valueOf(delivery.get().getStatus());
            // 로컬 레지스트리(SseRelay)를 직접 부르지 않는 것이 핵심이다 — 고객은 다른 인스턴스에
            // 연결돼 있을 수 있다(#317). TrackingPublisher 는 스스로 예외를 삼킨다.
            //
            // 상태를 실은 사본만 발행한다(#449) — 저장소로 간 원본에는 상태가 없다. 주기적으로
            // 흐르는 이 프레임이 일회성 상태 전이 이벤트의 유실을 최대 5초 안에 덮는다.
            trackingPublisher.publish(delivery.get().getOrderId(), location.withStatus(status));
        } catch (RuntimeException e) {
            log.warn("event=RIDER_LOCATION_RELAY_FAILED riderId={} reason={}",
                    rider.memberId(), e.getClass().getSimpleName(), e);
        }
    }
}
