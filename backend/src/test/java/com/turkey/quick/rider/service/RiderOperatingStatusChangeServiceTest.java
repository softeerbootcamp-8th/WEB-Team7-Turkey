package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.location.repository.RiderGeoRepository;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
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
 * 운행 상태 변경(#54)의 전이·멱등·BUSY 거부·위치 정리 로직 검증. 스프링·DB 없이 리포지토리와 위치
 * 저장소를 목으로 두고 순수하게 본다({@code RiderLogoutServiceTest}와 같은 방식).
 */
class RiderOperatingStatusChangeServiceTest {

    private static final Long MEMBER_ID = 1L;

    private RiderProfileRepository riderProfileRepository;
    private RiderGeoRepository riderGeoRepository;
    private RiderOperatingStatusChangeService service;

    @BeforeEach
    void setUp() {
        riderProfileRepository = mock(RiderProfileRepository.class);
        riderGeoRepository = mock(RiderGeoRepository.class);
        service = new RiderOperatingStatusChangeService(riderProfileRepository, riderGeoRepository);
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
    @DisplayName("콜 받기: UNAVAILABLE 라이더가 GO_ONLINE 하면 AVAILABLE 이 되고 위치는 건드리지 않는다")
    void goOnlineFromUnavailableBecomesAvailable() {
        RiderProfile profile = profileWith(OperatingStatus.UNAVAILABLE);

        RiderOperatingStatusResponse response = service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_ONLINE);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
        verify(riderGeoRepository, never()).remove(MEMBER_ID);
    }

    @Test
    @DisplayName("운행 종료: AVAILABLE 라이더가 GO_OFFLINE 하면 UNAVAILABLE 이 되고 최신 위치를 지운다")
    void goOfflineFromAvailableDeletesLocation() {
        RiderProfile profile = profileWith(OperatingStatus.AVAILABLE);

        RiderOperatingStatusResponse response = service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_OFFLINE);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
        verify(riderGeoRepository).remove(MEMBER_ID); // 배차 후보 즉시 제외
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
    @DisplayName("멱등: 이미 UNAVAILABLE 인데 GO_OFFLINE 하면 전이·위치 삭제 없이 현재 상태를 반환한다")
    void goOfflineWhileUnavailableIsIdempotent() {
        RiderProfile profile = profileWith(OperatingStatus.UNAVAILABLE);

        RiderOperatingStatusResponse response = service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_OFFLINE);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
        verify(riderGeoRepository, never()).remove(MEMBER_ID);
    }

    @Test
    @DisplayName("BUSY 라이더의 직접 변경 요청은 409 로 거부되고 상태·위치가 그대로다")
    void busyRiderDirectChangeIsRejected() {
        RiderProfile profile = profileWith(OperatingStatus.BUSY);

        assertThatThrownBy(() -> service.changeOperatingStatus(MEMBER_ID, RiderOperatingAction.GO_OFFLINE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.BUSY);
        verify(riderGeoRepository, never()).remove(MEMBER_ID);
    }
}
