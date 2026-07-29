package com.turkey.quick.location.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.dto.LocationUpdateOutcome;
import com.turkey.quick.location.dto.RiderLocationSnapshot;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.dto.RiderLocationUpdateResponse;
import com.turkey.quick.rider.domain.OperatingStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 라이더 최신 위치 갱신(#81 처리 흐름 ①~④).
 *
 * <p><b>DB 를 읽지 않는다.</b> {@code RiderSessionInterceptor} 가 매 요청마다
 * {@code riderProfileRepository.findById} 로 프로필을 새로 읽어 운행 상태를
 * {@code AuthenticatedRider} 에 담아 주므로 여기서 다시 조회할 이유가 없다. 다시 읽어도 경쟁은
 * 막지 못한다 — 라이더가 운행을 종료하는 순간에 이미 처리 중이던 요청은 어느 쪽으로 읽어도
 * 통과할 수 있고, 그 결과는 "TTL 10분짜리 위치가 한 번 더 저장됨"이라 감내 가능하다.
 * 따라서 트랜잭션 경계도 없다 — {@code @Transactional} 을 붙이면 위치 전송마다 아무 일도 하지
 * 않는 DB 트랜잭션이 열린다.
 *
 * <p>거리·속도 기반 중복·이상 이동 필터는 여기 없다(#82). 반면 <b>측정 시각 비교는 여기</b>
 * 있다 — #81 흐름 ③ 이고, 트래픽 최적화가 아니라 "최신 위치"라는 값의 정합성 문제다.
 *
 * <p>폐기 판정(STALE·LOW_ACCURACY·NON_MONOTONIC)에서는 저장도 하지 않는다. 값 자체를 믿을 수
 * 없는 경우이기 때문이다. "저장은 하되 전파하지 않는다"는 예외는 #82 의 최소 이동 거리 미달
 * (정지한 라이더의 TTL 갱신)에만 해당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderLocationService {

    private static final String NOT_OPERATING_MESSAGE = "운행 중이 아닙니다.";

    /**
     * 위치를 받을 수 있는 운행 상태. {@code == UNAVAILABLE} 로 뒤집어 쓰지 않는 이유는, 나중에
     * 상태가 하나 늘었을 때 <b>거부가 아니라 허용</b>으로 조용히 새는 쪽이 더 위험하기 때문이다.
     */
    private static final Set<OperatingStatus> LOCATION_ALLOWED_STATUSES =
            EnumSet.of(OperatingStatus.AVAILABLE, OperatingStatus.BUSY);

    private final RiderLocationStore riderLocationStore;

    /**
     * @param riderId         세션에서 얻은 라이더 식별자(= member_id)
     * @param operatingStatus 세션 인터셉터가 이번 요청에 읽어 온 현재 운행 상태
     * @throws BusinessException        운행 중이 아닌 경우(409)
     * @throws IllegalArgumentException 측정 시각이 허용 오차를 넘어 미래인 경우(400).
     *                                 잡지 않고 그대로 올려보낸다 — 감싸면 메시지가 바뀐다.
     */
    public RiderLocationUpdateResponse update(Long riderId, OperatingStatus operatingStatus,
                                              RiderLocationUpdateRequest request) {
        if (!LOCATION_ALLOWED_STATUSES.contains(operatingStatus)) {
            // 운행 종료 후에도 클라이언트가 전송을 멈추지 않았다는 뜻이라 warn 이다.
            log.warn("event=LOCATION_REJECTED riderId={} operatingStatus={} reason={}",
                    riderId, operatingStatus, "NOT_OPERATING");
            throw new BusinessException(HttpStatus.CONFLICT, NOT_OPERATING_MESSAGE);
        }

        LocationUpdateOutcome outcome = LocationAcceptancePolicy.evaluate(request, Instant.now());
        if (!outcome.isAccepted()) {
            return discard(riderId, outcome);
        }

        RiderLocationSnapshot snapshot = request.toSnapshot();
        if (isOutOfOrder(riderId, snapshot.measuredAt())) {
            return discard(riderId, LocationUpdateOutcome.NON_MONOTONIC);
        }

        riderLocationStore.save(riderId, snapshot);
        // published 는 SSE 발행(#78)이 붙기 전까지 항상 false 다.
        return RiderLocationUpdateResponse.accept(false);
    }

    /**
     * 이전 최신 위치보다 과거이거나 <b>같은</b> 시각이면 순서가 어긋난 것으로 본다.
     * 같은 시각까지 막는 이유: 동일 좌표 재전송이라 덮어써도 값은 같은데 Redis 쓰기만 늘어난다.
     */
    private boolean isOutOfOrder(Long riderId, LocalDateTime measuredAt) {
        return riderLocationStore.find(riderId)
                .map(RiderLocationSnapshot::measuredAt)
                .filter(previous -> !measuredAt.isAfter(previous))
                .isPresent();
    }

    /**
     * 폐기는 실내 측위·탭 복귀처럼 정상 운행 중에도 일어나므로 warn 이 아니라 info 다.
     * warn 으로 두면 정상 동작이 경고 로그를 채워 진짜 문제가 묻힌다.
     */
    private RiderLocationUpdateResponse discard(Long riderId, LocationUpdateOutcome outcome) {
        log.info("event=LOCATION_DISCARDED riderId={} reason={}", riderId, outcome);
        return RiderLocationUpdateResponse.discard(outcome);
    }
}
