package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderOperatingStatusResponse;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운행 상태 조회(#53) 통합 테스트. JPA 매핑과 "진행 중 배송" 조회가 실제 MySQL 에서 성립하는지
 * 검증한다 — 특히 active_rider_id 생성 컬럼의 채움 조건({@code OrderStatus.riderActiveStatuses})과
 * 조회 결과가 일치하는지는 단위 테스트로 잡히지 않는다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderOperatingStatusQueryServiceIntegrationTest extends IntegrationTestSupport {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private RiderOperatingStatusQueryService service;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RiderProfileRepository riderProfileRepository;

    @Autowired
    private DeliveryOrderRepository deliveryOrderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private Long saveRider(String loginId, String phone, OperatingStatus status) {
        return tx().execute(t -> {
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

    /** 라이더에게 배차된 진행 중(ASSIGNED) 주문을 만들어 그 id 를 돌려준다. */
    private Long saveAssignedOrder(Long riderId, String customerLoginId, String customerPhone) {
        return tx().execute(t -> {
            Member customer = memberRepository.save(
                    Member.create(customerLoginId, PASSWORD_ENCODER.encode("p@ssw0rd"), "김고객", customerPhone, MemberRole.CUSTOMER));
            RiderProfile rider = riderProfileRepository.findById(riderId).orElseThrow();
            DeliveryOrder order = DeliveryOrder.request(customer, UUID.randomUUID().toString(),
                    ItemType.DOCUMENT, 1500,
                    Address.of("서울 강남대로 1", "101호", "06000", new BigDecimal("37.5000000"), new BigDecimal("127.0000000")),
                    Address.of("서울 테헤란로 2", "202호", "06001", new BigDecimal("37.5010000"), new BigDecimal("127.0300000")),
                    Contact.of("보내는이", "01011112222"),
                    Contact.of("받는이", "01033334444"));
            order.assign(rider); // WAITING → ASSIGNED, assigned_rider_id 채움
            return deliveryOrderRepository.save(order).getId();
        });
    }

    @Test
    @DisplayName("BUSY 라이더는 진행 중 배송 식별자와 함께 조회된다")
    void busyRiderReturnsCurrentDeliveryId() {
        Long riderId = saveRider("int_busy", "01000000001", OperatingStatus.BUSY);
        Long orderId = saveAssignedOrder(riderId, "int_busy_cust", "01000000002");

        RiderOperatingStatusResponse response = service.getOperatingStatus(riderId);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.BUSY);
        assertThat(response.currentDeliveryId()).isEqualTo(orderId);
        assertThat(response.statusChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("AVAILABLE 라이더는 진행 중 배송이 없어 currentDeliveryId 가 null 이다")
    void availableRiderHasNoCurrentDelivery() {
        Long riderId = saveRider("int_avail", "01000000003", OperatingStatus.AVAILABLE);

        RiderOperatingStatusResponse response = service.getOperatingStatus(riderId);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.AVAILABLE);
        assertThat(response.currentDeliveryId()).isNull();
    }

    @Test
    @DisplayName("UNAVAILABLE 라이더도 진행 중 배송 없이 상태만 조회된다")
    void unavailableRiderReturnsStatusOnly() {
        Long riderId = saveRider("int_unavail", "01000000005", OperatingStatus.UNAVAILABLE);

        RiderOperatingStatusResponse response = service.getOperatingStatus(riderId);

        assertThat(response.operatingStatus()).isEqualTo(OperatingStatus.UNAVAILABLE);
        assertThat(response.currentDeliveryId()).isNull();
    }
}
