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
import java.time.Duration;
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
 * 라이더 로그아웃의 트랜잭션 경계 검증(#51). 실제 MySQL에 붙어, 상태 전이가 커밋되는지와
 * 세션 삭제가 커밋 이후(afterCommit)에만 일어나는지를 확인한다. 단위 테스트는 트랜잭션이 없어
 * 세션 삭제가 동기적으로 일어나므로 이 경로는 여기서만 검증된다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderLogoutServiceIntegrationTest extends IntegrationTestSupport {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final Duration TTL = Duration.ofHours(2);

    @Autowired
    private RiderLogoutService riderLogoutService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private InMemorySessionStore sessionStore;

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

    private String createSession(Long memberId) {
        String sessionId = "session-" + memberId;
        sessionStore.create(sessionId, memberId, "RIDER", TTL);
        return sessionId;
    }

    @Test
    @DisplayName("AVAILABLE 라이더 로그아웃: DB 상태가 UNAVAILABLE로 커밋되고 세션이 삭제된다")
    void availableRiderLogoutCommitsUnavailableAndDeletesSession() {
        Long memberId = saveRiderWithStatus("int_logout_avail", "01011112222", OperatingStatus.AVAILABLE);
        String sessionId = createSession(memberId);

        riderLogoutService.logout(sessionId);

        // 서비스 트랜잭션이 커밋된 뒤 새 조회로 확인한다.
        assertThat(riderProfileRepository.findById(memberId)).get()
                .extracting(RiderProfile::getOperatingStatus)
                .isEqualTo(OperatingStatus.UNAVAILABLE);
        assertThat(sessionStore.get(sessionId)).isNull();
    }

    @Test
    @DisplayName("BUSY 라이더 로그아웃: 409로 거부되고 DB 상태와 세션이 그대로 유지된다")
    void busyRiderLogoutIsRejectedAndNothingIsPersisted() {
        Long memberId = saveRiderWithStatus("int_logout_busy", "01022223333", OperatingStatus.BUSY);
        String sessionId = createSession(memberId);

        assertThatThrownBy(() -> riderLogoutService.logout(sessionId))
                .isInstanceOf(BusinessException.class);

        assertThat(riderProfileRepository.findById(memberId)).get()
                .extracting(RiderProfile::getOperatingStatus)
                .isEqualTo(OperatingStatus.BUSY);
        assertThat(sessionStore.get(sessionId)).isNotNull();
    }

    @Test
    @DisplayName("UNAVAILABLE 라이더 로그아웃: 상태는 그대로 두고 세션만 삭제한다")
    void unavailableRiderLogoutKeepsStatusAndDeletesSession() {
        Long memberId = saveRiderWithStatus("int_logout_unavail", "01033334444", OperatingStatus.UNAVAILABLE);
        String sessionId = createSession(memberId);

        riderLogoutService.logout(sessionId);

        assertThat(riderProfileRepository.findById(memberId)).get()
                .extracting(RiderProfile::getOperatingStatus)
                .isEqualTo(OperatingStatus.UNAVAILABLE);
        assertThat(sessionStore.get(sessionId)).isNull();
    }
}
