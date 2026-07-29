package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 라이더 로그아웃의 도메인 분기 검증(#51). 스프링 컨텍스트 없이 순수 객체로 돌린다.
 * 트랜잭션 동기화가 활성화되지 않은 상태이므로 세션 삭제가 동기적으로 일어난다
 * (afterCommit 지연은 실제 트랜잭션이 있는 통합 테스트에서 검증한다).
 */
class RiderLogoutServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final String SESSION_ID = "session-abc";
    private static final Duration TTL = Duration.ofHours(2);

    private InMemorySessionStore sessionStore;
    private RiderProfileRepository riderProfileRepository;
    private RiderLogoutService riderLogoutService;

    @BeforeEach
    void setUp() {
        sessionStore = new InMemorySessionStore();
        riderProfileRepository = mock(RiderProfileRepository.class);
        riderLogoutService = new RiderLogoutService(sessionStore, riderProfileRepository);
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

    @Nested
    @DisplayName("운행 상태에 따른 처리")
    class ByOperatingStatus {

        @Test
        @DisplayName("AVAILABLE 라이더는 UNAVAILABLE로 바뀌고 세션이 삭제된다")
        void availableRiderGoesOfflineAndSessionIsDeleted() {
            RiderProfile profile = profileWith(OperatingStatus.AVAILABLE);
            sessionStore.create(SESSION_ID, MEMBER_ID, "RIDER", TTL);

            riderLogoutService.logout(SESSION_ID);

            assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
            assertThat(sessionStore.get(SESSION_ID)).isNull();
        }

        @Test
        @DisplayName("UNAVAILABLE 라이더는 상태 그대로 세션만 삭제된다")
        void unavailableRiderKeepsStatusAndSessionIsDeleted() {
            RiderProfile profile = profileWith(OperatingStatus.UNAVAILABLE);
            sessionStore.create(SESSION_ID, MEMBER_ID, "RIDER", TTL);

            riderLogoutService.logout(SESSION_ID);

            assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
            assertThat(sessionStore.get(SESSION_ID)).isNull();
        }

        @Test
        @DisplayName("BUSY 라이더는 409로 거부되고 상태·세션이 유지된다")
        void busyRiderIsRejectedAndNothingChanges() {
            RiderProfile profile = profileWith(OperatingStatus.BUSY);
            sessionStore.create(SESSION_ID, MEMBER_ID, "RIDER", TTL);

            assertThatThrownBy(() -> riderLogoutService.logout(SESSION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

            assertThat(profile.getOperatingStatus()).isEqualTo(OperatingStatus.BUSY);
            assertThat(sessionStore.get(SESSION_ID)).isNotNull();
        }
    }

    @Nested
    @DisplayName("멱등 처리")
    class Idempotent {

        @Test
        @DisplayName("세션 쿠키가 없으면(null) 아무 조회도 하지 않고 조용히 끝난다")
        void nullSessionIsNoOp() {
            riderLogoutService.logout(null);

            verify(riderProfileRepository, never()).findById(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("이미 만료·삭제된 세션이면 라이더 조회 없이 조용히 끝난다")
        void unknownSessionIsNoOp() {
            riderLogoutService.logout("no-such-session");

            verify(riderProfileRepository, never()).findById(org.mockito.ArgumentMatchers.any());
        }
    }
}
