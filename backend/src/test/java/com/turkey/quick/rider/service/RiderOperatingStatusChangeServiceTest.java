package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderOperatingAction;
import com.turkey.quick.rider.dto.RiderOperatingStatusResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 운행 상태 변경(#54)의 전이·멱등·BUSY 거부 로직 검증. 스프링·DB 없이 리포지토리를 목으로 두고
 * 순수하게 본다({@code RiderLogoutServiceTest}와 같은 방식).
 *
 * <p>라이더-측 GEO 사용처를 제거하면서(#342, 디스커션 #338) 운행 종료 시 배차 후보 GEO 정리도
 * 사라졌다 — 이제 GO_OFFLINE 은 상태 전이만 한다.
 */
class RiderOperatingStatusChangeServiceTest {

    private static final Long MEMBER_ID = 1L;

    private RiderProfileRepository riderProfileRepository;
    private DeliveryOrderRepository deliveryOrderRepository;
    private RiderOperatingStatusChangeService service;

    @BeforeEach
    void setUp() {
        riderProfileRepository = mock(RiderProfileRepository.class);
        deliveryOrderRepository = mock(DeliveryOrderRepository.class);
        service = new RiderOperatingStatusChangeService(
                riderProfileRepository, deliveryOrderRepository);
    }

    private RiderProfile profileWith(OperatingStatus status) {
        Member member = Member.create("rider1", "hash", "홍길동", "01011112222", MemberRole.RIDER);
        RiderProfile profile = RiderProfile.create(member); // UNAVAILABLE
        if (status == OperatingStatus.AVAILABLE) {
            profile.goOnline();
        } else if (status == OperatingStatus.BUSY) {
            profile.goOnline();
            profile.assign();
        }
        when(riderProfileRepository.findById(MEMBER_ID)).thenReturn(Optional.of(profile));
        return profile;
    }

    @Test
    @DisplayName("콜 받기: UNAVAILABLE 라이더가 GO_ONLINE 하면 AVAILABLE 이 된다")
    void goOnlineFromUnavailableBecomesAvailable() {
        RiderProfile profile = profileWith(OperatingStatus.UNAVAILABLE);

        RiderOperatingStatusResponse response = service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_ONLINE);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
    }

    @Test
    @DisplayName("운행 종료: AVAILABLE 라이더가 GO_OFFLINE 하면 UNAVAILABLE 이 된다")
    void goOfflineFromAvailableBecomesUnavailable() {
        RiderProfile profile = profileWith(OperatingStatus.AVAILABLE);

        RiderOperatingStatusResponse response = service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_OFFLINE);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("멱등: 이미 AVAILABLE 인데 GO_ONLINE 하면 전이 없이 현재 상태를 반환한다")
    void goOnlineWhileAvailableIsIdempotent() {
        RiderProfile profile = profileWith(OperatingStatus.AVAILABLE);

        RiderOperatingStatusResponse response = service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_ONLINE);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
    }

    @Test
    @DisplayName("멱등: 이미 UNAVAILABLE 인데 GO_OFFLINE 하면 전이 없이 현재 상태를 반환한다")
    void goOfflineWhileUnavailableIsIdempotent() {
        RiderProfile profile = profileWith(OperatingStatus.UNAVAILABLE);

        RiderOperatingStatusResponse response = service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_OFFLINE);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("BUSY 라이더의 직접 변경 요청은 409 로 거부되고 상태가 그대로다")
    void busyRiderDirectChangeIsRejected() {
        RiderProfile profile = profileWith(OperatingStatus.BUSY);

        assertThatThrownBy(() -> service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_OFFLINE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.BUSY);
    }

    @Test
    @DisplayName("진행 배송이 있는데 BUSY가 아닌 라이더의 상태 변경은 정합성 오류로 거부한다")
    void activeDeliveryWithNonBusyRiderIsRejected() {
        RiderProfile profile = profileWith(OperatingStatus.AVAILABLE);
        when(deliveryOrderRepository.existsByAssignedRider_MemberIdAndStatusIn(
                MEMBER_ID, OrderStatus.trackableStatuses())).thenReturn(true);

        assertThatThrownBy(() -> service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_OFFLINE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
    }
}
