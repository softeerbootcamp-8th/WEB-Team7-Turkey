package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderOperatingAction;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운행 상태 변경(#54) 통합 테스트. 상태 전이가 실제 MySQL 에 영속되는지(재조회 유지)와 BUSY 거부 시
 * 롤백되는지를 검증한다. 운행 종료 시 GEO 배차 후보 정리는 라이더-측 GEO 사용처 제거(#342)로 사라져
 * 더는 검증 대상이 아니다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderOperatingStatusChangeServiceIntegrationTest extends IntegrationTestSupport {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private RiderOperatingStatusChangeService service;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long saveRider(String loginId, String phone, OperatingStatus status) {
        return new TransactionTemplate(transactionManager).execute(t -> {
            Member member = memberRepository.save(
                    Member.create(loginId, PASSWORD_ENCODER.encode("p@ssw0rd"), "홍길동", phone, MemberRole.RIDER));
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

    private OperatingStatus reloadStatus(Long riderId) {
        return riderProfileRepository.findById(riderId).orElseThrow().getOperatingStatus();
    }

    @Test
    @DisplayName("콜 받기 전이가 DB 에 영속되어 재조회 시에도 AVAILABLE 로 유지된다")
    void goOnlinePersists() {
        Long riderId = saveRider("int_go_online", "01000000101", OperatingStatus.UNAVAILABLE);

        service.changeOperatingStatus(riderId, RiderOperatingAction.GO_ONLINE);

        assertThat(reloadStatus(riderId)).isEqualTo(OperatingStatus.AVAILABLE);
    }

    @Test
    @DisplayName("운행 종료 시 UNAVAILABLE 로 영속된다")
    void goOfflinePersists() {
        Long riderId = saveRider("int_go_offline", "01000000102", OperatingStatus.AVAILABLE);

        service.changeOperatingStatus(riderId, RiderOperatingAction.GO_OFFLINE);

        assertThat(reloadStatus(riderId)).isEqualTo(OperatingStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("BUSY 라이더의 변경 요청은 409 로 거부되고 상태가 그대로 유지된다")
    void busyRiderIsRejectedAndStatusUnchanged() {
        Long riderId = saveRider("int_busy_change", "01000000103", OperatingStatus.BUSY);

        assertThatThrownBy(() -> service.changeOperatingStatus(riderId, RiderOperatingAction.GO_OFFLINE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(reloadStatus(riderId)).isEqualTo(OperatingStatus.BUSY);
    }

    @Test
    @DisplayName("이미 같은 상태로 요청하면 오류 없이 현재 상태를 유지한다(멱등)")
    void sameStatusRequestIsIdempotent() {
        Long riderId = saveRider("int_idem", "01000000104", OperatingStatus.AVAILABLE);

        service.changeOperatingStatus(riderId, RiderOperatingAction.GO_ONLINE); // 이미 AVAILABLE

        assertThat(reloadStatus(riderId)).isEqualTo(OperatingStatus.AVAILABLE);
    }
}
