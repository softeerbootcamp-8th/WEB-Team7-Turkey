package com.turkey.quick.location.sse;

import com.turkey.quick.location.dto.RiderLocationSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>단위 테스트</b> 전용 발행 대체. 무엇이 발행됐는지 기록하고, 실패·예외를 흉내낼 수 있다.
 *
 * <p>목킹 라이브러리를 쓰지 않는 이유는 이 저장소의 관례다({@code InMemoryRiderLocationStore},
 * {@code FakeSmsSender}와 같은 방식). 검증하려는 것이 "몇 번 호출됐나"가 아니라 <b>어떤 판정에서
 * 발행되고 어떤 판정에서 발행되지 않는가</b>라, 기록을 직접 들여다보는 편이 읽기 쉽다.
 */
public class RecordingTrackingEventPublisher implements TrackingEventPublisher {

    private final List<RiderLocationSnapshot> published = new ArrayList<>();
    private boolean hasInProgressDelivery = true;
    private RuntimeException failure;

    /**
     * 라이더에게 진행 중인 배송이 없는 상황(배차 전이거나 완료 후). {@code AVAILABLE} 라이더의
     * 30초 주기 위치가 대부분 이 경우다 — 발행할 주문 채널이 없어 false 를 돌려준다.
     */
    public void withoutInProgressDelivery() {
        this.hasInProgressDelivery = false;
    }

    /**
     * 계약을 어기고 예외를 던지는 구현을 흉내낸다. 서비스가 그래도 200 을 돌려주는지 확인하기
     * 위한 것이다 — 이 경로가 깨지면 Redis·MySQL 장애가 배차 후보 검색이 쓰는 최신 위치 갱신까지
     * 같이 죽인다.
     */
    public void throwing(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public boolean publish(Long riderId, RiderLocationSnapshot location) {
        if (failure != null) {
            throw failure;
        }
        if (!hasInProgressDelivery) {
            return false;
        }
        published.add(location);
        return true;
    }

    public List<RiderLocationSnapshot> published() {
        return List.copyOf(published);
    }
}
