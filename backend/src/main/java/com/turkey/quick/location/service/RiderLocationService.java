package com.turkey.quick.location.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.LocationPayload;
import com.turkey.quick.location.repository.RiderGeoRepository;
import com.turkey.quick.location.repository.RiderLocationRepository;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 라이더 위치 갱신. 요청은 좌표만 담고 있으므로 <b>이 서비스가 배송 식별자를 DB 로 직접 풀어낸다.</b>
 *
 * <p>운행 상태에 따라 하는 일이 다르다:
 * <table border="1">
 *   <caption>운행 상태별 처리</caption>
 *   <tr><th>상태</th><th>GEO 배차 후보</th><th>최신 위치</th><th>추적 팬아웃</th></tr>
 *   <tr><td>AVAILABLE</td><td>이 좌표로 등록·갱신</td><td>저장하지 않음</td><td>발행하지 않음</td></tr>
 *   <tr><td>BUSY</td><td>후보에서 제거</td><td>저장</td><td>수행 중 배송 채널로 발행</td></tr>
 *   <tr><td>UNAVAILABLE</td><td colspan="3">409 — 운행 중이 아니다</td></tr>
 * </table>
 *
 * <p>AVAILABLE 라이더에게 <b>발행할 채널이 없다</b>는 것이 핵심이다. 배송을 맡지 않은 라이더를
 * 추적하고 있는 고객은 존재할 수 없다. 예전에는 클라이언트가 배송 id 를 요청 본문에 실어 보내
 * AVAILABLE 라이더도 아무 채널로나 발행할 수 있었다(#291 의 구멍).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderLocationService {

    // 허용 목록으로 두는 이유: 나중에 상태가 하나 늘었을 때 거부가 아니라 허용으로 조용히
    // 새는 쪽이 더 위험하다.
    private static final Set<OperatingStatus> LOCATION_ALLOWED_STATUSES =
            EnumSet.of(OperatingStatus.AVAILABLE, OperatingStatus.BUSY);

    private final DeliveryOrderRepository deliveryOrderRepository;
    private final RiderGeoRepository riderGeoRepository;
    private final RiderLocationRepository riderLocationRepository;
    private final TrackingPublisher trackingPublisher;

    /**
     * <p><b>{@code @Transactional} 을 붙이지 않았다.</b> 리포지토리 호출이 스스로 트랜잭션을 열고
     * 끝내며, Redis 쓰기와 팬아웃 발행은 그 밖에서 일어난다. 여기에 트랜잭션을 걸면 Redis 왕복과
     * Pub/Sub 발행이 DB 커넥션을 잡은 채로 진행된다.
     */
    public void update(AuthenticatedRider rider, LocationPayload location) {
        if (!LOCATION_ALLOWED_STATUSES.contains(rider.operatingStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "운행 중이 아닙니다.");
        }
        syncRedisState(rider, location);
        if (rider.operatingStatus() == OperatingStatus.BUSY) {
            relayToTrackingCustomers(rider, location);
        }
    }

    /**
     * 배차 후보 반영(#83)과 최신 위치 저장(#317). 둘 다 Redis 쓰기이고 실패 처리가 같아 한 번에 묶는다.
     *
     * <p><b>최신 위치 저장을 {@code else} 에 얹지 않은 이유</b>는 위 허용 목록과 같다 —
     * {@code else} 는 "BUSY"가 아니라 "AVAILABLE 이 아님"이라, 허용 상태가 하나 늘면 그 상태의 위치가
     * <b>조용히 저장되기 시작한다.</b> 반면 GEO 쪽 {@code else} 는 그대로 둔다 — {@code remove} 는
     * 멱등이고 "후보에서 뺀다"는 새 상태가 흘러들어도 항상 안전한 방향이다.
     *
     * <p>Redis 갱신이 실패해도 이 요청 자체는 계속 성공해야 하므로 예외를 삼키고 로깅만 한다.
     * 배차 후보는 실패하면 그 라이더가 후보에서 빠진 채로 남는 것 자체가 "잘못된 후보를 쓰지
     * 않는다"는 안전장치이고, 최신 위치는 다음 전송(BUSY 5초 주기)이 복구한다.
     */
    private void syncRedisState(AuthenticatedRider rider, LocationPayload location) {
        try {
            if (rider.operatingStatus() == OperatingStatus.AVAILABLE) {
                riderGeoRepository.registerOrUpdate(rider.memberId(), location.latitude(), location.longitude());
            } else {
                riderGeoRepository.remove(rider.memberId());
            }
            if (rider.operatingStatus() == OperatingStatus.BUSY) {
                riderLocationRepository.saveIfNewer(rider.memberId(), location);
            }
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
     * {@link DeliveryOrderRepository#findInProgressIdByActiveRiderId} 를 쓴다 — 주문 이력이 쌓여도
     * 비용이 늘지 않는다.
     *
     * 조회(5초마다 다시 조회하잖아)를 없애고 라이더→배송 매핑을 캐시하지 않는 이유:
     * 5초마다 조회하는 비용을 내고 개인정보 유출(세션 안끝난 고객이 다른 실시간 정보 조회) 버그의 가능성을 원천 차단
     *
     * 조회 실패가 위치 갱신(syncRedisState)을 실패시키지 않는다. 이 경로에 MySQL 의존(조회)이 새로 생겼으므로,
     * DB 장애가 배차 후보 갱신(#83)과 최신 위치 저장까지 같이 죽이지 않도록 예외를 삼킨다. 전달은
     * at-most-once 이고 다음 전송(5초)이 복구한다.
     *
     * <p>수행 중 배송이 없는 BUSY 라이더는 <b>정합성이 깨진 상태</b>다.
     * 이때 WARN 을 남긴다 — 배송 완료 처리와 라이더 상태 전이가 어긋났을 때 여기로 드러난다.
     */
    private void relayToTrackingCustomers(AuthenticatedRider rider, LocationPayload location) {
        try {
            Optional<Long> deliveryId = deliveryOrderRepository
                    .findInProgressIdByActiveRiderId(rider.memberId());
            if (deliveryId.isEmpty()) {
                log.warn("event=RIDER_LOCATION_RELAY_SKIPPED riderId={} reason={}",
                        rider.memberId(), "NO_IN_PROGRESS_DELIVERY");
                return;
            }
            // 로컬 레지스트리(SseRelay)를 직접 부르지 않는 것이 핵심이다 — 고객은 다른 인스턴스에
            // 연결돼 있을 수 있다(#317). TrackingPublisher 는 스스로 예외를 삼킨다.
            trackingPublisher.publish(deliveryId.get(), location);
        } catch (RuntimeException e) {
            log.warn("event=RIDER_LOCATION_RELAY_FAILED riderId={} reason={}",
                    rider.memberId(), e.getClass().getSimpleName(), e);
        }
    }
}
