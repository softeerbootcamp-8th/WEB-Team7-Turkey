package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 라이더 로그아웃의 운행 상태 전이 검증(#51). 세션 삭제·쿠키 만료는 컨트롤러 책임이라 이 서비스는
 * 오직 상태만 다루고, 그래서 스프링·세션 스토어 없이 리포지토리 목만으로 순수하게 검증한다.
 */
class RiderLogoutServiceTest {

    private static final Long MEMBER_ID = 1L;

    private RiderProfileRepository riderProfileRepository;
    private RiderLogoutService riderLogoutService;

    @BeforeEach
    void setUp() {
        riderProfileRepository = mock(RiderProfileRepository.class);
        riderLogoutService = new RiderLogoutService(riderProfileRepository);
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
    @DisplayName("AVAILABLE 라이더는 UNAVAILABLE로 전이된다")
    void availableRiderGoesOffline() {
        RiderProfile profile = profileWith(OperatingStatus.AVAILABLE);

        riderLogoutService.changeStatusForLogout(MEMBER_ID);

        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("UNAVAILABLE 라이더는 상태가 그대로 유지된다")
    void unavailableRiderKeepsStatus() {
        RiderProfile profile = profileWith(OperatingStatus.UNAVAILABLE);

        riderLogoutService.changeStatusForLogout(MEMBER_ID);

        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("BUSY 라이더는 409로 거부되고 상태가 유지된다")
    void busyRiderIsRejected() {
        RiderProfile profile = profileWith(OperatingStatus.BUSY);

        assertThatThrownBy(() -> riderLogoutService.changeStatusForLogout(MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.BUSY);
    }
}
