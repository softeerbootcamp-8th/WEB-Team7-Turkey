package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.common.auth.InMemorySessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 라이더 로그아웃 상태 전이의 트랜잭션 경계 검증(#51). 실제 MySQL에 붙어, AVAILABLE→UNAVAILABLE
 * 전이가 커밋되는지와 BUSY 거부 시 아무것도 영속되지 않는지를 확인한다.
 * (세션 삭제·쿠키 만료는 컨트롤러 책임이라 여기서 검증하지 않는다 — E2E가 전 경로로 확인한다.)
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderLogoutServiceIntegrationTest extends IntegrationTestSupport {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private RiderLogoutService riderLogoutService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @TestConfiguration
    static class FakeInfraConfig {

        @Bean
        @Primary
        InMemorySessionStore sessionStore() {
            return new InMemorySessionStore();
        }
    }

    private Long saveRiderWithStatus(String loginId, String phoneNumber, OperatingStatus status) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode("p@ssw0rd"), "홍길동", phoneNumber, MemberRole.RIDER));
            RiderProfile profile = RiderProfile.create(member); // UNAVAILABLE
            if (status == OperatingStatus.AVAILABLE) {
                profile.goOnline();
            } else if (status == OperatingStatus.BUSY) {
                profile.goOnline();
                profile.assign();
            }
            riderProfileRepository.save(profile);
            return member.getId();
        });
    }

    @Test
    @DisplayName("AVAILABLE 라이더 상태 전이가 UNAVAILABLE로 커밋된다")
    void availableRiderCommitsUnavailable() {
        Long memberId = saveRiderWithStatus("int_logout_avail", "01011112222", OperatingStatus.AVAILABLE);

        riderLogoutService.changeStatusForLogout(memberId);

        // 서비스 트랜잭션이 커밋된 뒤 새 조회로 확인한다.
        assertThat(riderProfileRepository.findById(memberId)).get()
                .extracting(RiderProfile::getOperatingStatus)
                .isEqualTo(OperatingStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("BUSY 라이더는 409로 거부되고 DB 상태가 그대로 유지된다")
    void busyRiderIsRejectedAndNothingIsPersisted() {
        Long memberId = saveRiderWithStatus("int_logout_busy", "01022223333", OperatingStatus.BUSY);

        assertThatThrownBy(() -> riderLogoutService.changeStatusForLogout(memberId))
                .isInstanceOf(BusinessException.class);

        assertThat(riderProfileRepository.findById(memberId)).get()
                .extracting(RiderProfile::getOperatingStatus)
                .isEqualTo(OperatingStatus.BUSY);
    }

    @Test
    @DisplayName("UNAVAILABLE 라이더는 상태가 그대로 유지된다")
    void unavailableRiderKeepsStatus() {
        Long memberId = saveRiderWithStatus("int_logout_unavail", "01033334444", OperatingStatus.UNAVAILABLE);

        riderLogoutService.changeStatusForLogout(memberId);

        assertThat(riderProfileRepository.findById(memberId)).get()
                .extracting(RiderProfile::getOperatingStatus)
                .isEqualTo(OperatingStatus.UNAVAILABLE);
    }
}
