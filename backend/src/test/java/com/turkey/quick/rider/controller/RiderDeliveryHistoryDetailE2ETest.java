package com.turkey.quick.rider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.domain.MemberRole;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.repository.PointWalletRepository;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.dto.RiderDeliveryAction;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import com.turkey.quick.rider.service.RiderDeliveryService;
import com.turkey.quick.support.IntegrationTestSupport;
import com.turkey.quick.support.TrackingFixture;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운행 기록 상세 조회(#71) E2E. 이슈의 성공 조건("완료한 운행의 전체 상세정보 반환")과 예외
 * ("다른 라이더·존재하지 않는 주문 거부", "미로그인 요청 거부")를 실제 HTTP 로 통과시킨다.
 *
 * <p>완료+정산 상태는 완료 흐름({@code RiderDeliveryService.complete})으로 만든다 — 배차 HTTP 경로가
 * 없고({@code TrackingFixture} 와 같은 이유), {@code TrackingFixture} 의 COMPLETED 시나리오는 목록용이라
 * FINAL 스냅샷·정산을 만들지 않기 때문이다. 상세 로직 자체(필드 조립·게이트)는 통합 테스트가 촘촘히
 * 덮으므로 E2E 는 계약(상태 코드·응답 형태·인증)만 확인한다. 쿠키 없이 401 을 덮는 이유는 목록 E2E 와
 * 같다 — 인증이 {@code RiderWebMvcConfig.addPathPatterns} 등록에 달려 있다({@code /history/**} 커버).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class RiderDeliveryHistoryDetailE2ETest extends IntegrationTestSupport {

    private static final String LOGIN_ENDPOINT = "/api/rider/login";
    private static final String HISTORY_ENDPOINT = "/api/rider/history";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired private TestRestTemplate rest;
    @Autowired private RiderDeliveryService riderDeliveryService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private RiderProfileRepository riderProfileRepository;
    @Autowired private DeliveryOrderRepository deliveryOrderRepository;
    @Autowired private FarePolicyRepository farePolicyRepository;
    @Autowired private OrderFareSnapshotRepository orderFareSnapshotRepository;
    @Autowired private PointWalletRepository pointWalletRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("로그인한 라이더가 본인이 완료한 배송 상세를 조회하면 운임·정산·타임라인이 담겨 온다")
    void loggedInRiderSeesOwnDetail() {
        Completed completed = completedDelivery("e2e_hist71_a", "01071900001");
        String cookie = loginAndGetSessionCookie(completed.loginId());

        var response = rest.exchange(HISTORY_ENDPOINT + "/" + completed.orderId(), HttpMethod.GET,
                withCookie(cookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("data.deliveryId").isEqualTo(completed.orderId().intValue());
        assertThat(response.getBody()).extracting("data.finalFare").isNotNull();
        assertThat(response.getBody()).extracting("data.settlementAmount").isNotNull();
        assertThat(response.getBody()).extracting("data.steps").asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(Object.class)).isNotEmpty();
    }

    @Test
    @DisplayName("다른 라이더가 완료한 배송 상세를 조회하면 404 로 거부된다")
    void otherRidersDeliveryReturns404() {
        Completed owner = completedDelivery("e2e_hist71_b", "01071900002");
        String otherCookie = loginAndGetSessionCookie(onlineRiderLogin("e2e_hist71_c", "01071900003"));

        var response = rest.exchange(HISTORY_ENDPOINT + "/" + owner.orderId(), HttpMethod.GET,
                withCookie(otherCookie), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("세션 쿠키 없이 상세를 조회하면 401 로 거부된다")
    void withoutCookieReturns401() {
        var response = rest.exchange(HISTORY_ENDPOINT + "/1", HttpMethod.GET, withCookie(null), ApiResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 완료+정산까지 진행한 배송을 만든다. 라이더는 PASSWORD 로 실제 로그인할 수 있어야 한다. */
    private Completed completedDelivery(String loginId, String phone) {
        Ids ids = new TransactionTemplate(transactionManager).execute(t -> {
            Member riderMember = memberRepository.save(Member.create(
                    loginId, PASSWORD_ENCODER.encode(TrackingFixture.PASSWORD), "홍라이더", phone, MemberRole.RIDER));
            RiderProfile rider = RiderProfile.create(riderMember);
            pointWalletRepository.save(PointWallet.create(riderMember));
            rider.goOnline();
            rider.assign();
            riderProfileRepository.save(rider);

            Member customer = memberRepository.save(Member.create(
                    "c_" + loginId, "hash", "김고객", "019" + phone.substring(3), MemberRole.CUSTOMER));
            DeliveryOrder order = DeliveryOrder.request(customer, "req-" + loginId, ItemType.SMALL_PARCEL, 1000,
                    Address.of("픽업지", "상세", "12345", new BigDecimal("37.5000000"), new BigDecimal("127.0000000")),
                    Address.of("도착지", "상세", "54321", new BigDecimal("37.6000000"), new BigDecimal("127.1000000")),
                    Contact.of("보내는 사람", "01011112222"), Contact.of("받는 사람", "01033334444"));
            order.assign(rider);
            DeliveryOrder saved = deliveryOrderRepository.save(order);
            FarePolicy policy = farePolicyRepository.save(FarePolicy.create(
                    "v71e-" + loginId, 3000L, 100, 100L, 30000, LocalDateTime.now().minusDays(1)));
            orderFareSnapshotRepository.save(OrderFareSnapshot.create(
                    saved, policy, FareType.ESTIMATE, policy.getPolicyVersion(), 1000, 3000L, 100L, 0L));
            return new Ids(rider.getMemberId(), saved.getId());
        });

        AuthenticatedRider auth = new AuthenticatedRider(ids.riderId(), loginId, "홍라이더", OperatingStatus.BUSY);
        riderDeliveryService.transition(auth, ids.orderId(), RiderDeliveryAction.START_MOVING_TO_PICKUP);
        riderDeliveryService.transition(auth, ids.orderId(), RiderDeliveryAction.PICK_UP);
        riderDeliveryService.transition(auth, ids.orderId(), RiderDeliveryAction.START_DELIVERING);
        riderDeliveryService.complete(auth, ids.orderId(),
                new RiderDeliveryCompleteRequest(ProofType.RECIPIENT_CONFIRMATION, null, "recipient-" + loginId));
        return new Completed(loginId, ids.orderId());
    }

    private String onlineRiderLogin(String loginId, String phone) {
        new TransactionTemplate(transactionManager).executeWithoutResult(t -> {
            Member member = memberRepository.save(Member.create(
                    loginId, PASSWORD_ENCODER.encode(TrackingFixture.PASSWORD), "홍라이더", phone, MemberRole.RIDER));
            riderProfileRepository.save(RiderProfile.create(member));
        });
        return loginId;
    }

    private String loginAndGetSessionCookie(String loginId) {
        var response = rest.postForEntity(LOGIN_ENDPOINT,
                Map.of("loginId", loginId, "password", TrackingFixture.PASSWORD), ApiResponse.class);
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();
        return cookies.get(0).split(";")[0];
    }

    private HttpEntity<Void> withCookie(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return new HttpEntity<>(headers);
    }

    private record Ids(Long riderId, Long orderId) {
    }

    private record Completed(String loginId, Long orderId) {
    }
}
