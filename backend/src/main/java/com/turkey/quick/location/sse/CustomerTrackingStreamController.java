package com.turkey.quick.location.sse;

import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 매핑·검증 어노테이션을 재선언하지 않는다 — 인터페이스에 있는 것을 구현체에 다시 달면 Bean
 * Validation 이 HV000151 로 기동을 깬다({@code RiderLocationController} 와 같은 관례).
 *
 * <p>{@code AuthenticatedCustomer} 를 서비스에 그대로 넘기지 않고 {@code memberId()} 로 풀어
 * 넘긴다. 위치·주문 도메인이 {@code customer.auth} 패키지에 의존하지 않게 하려는 것이다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class CustomerTrackingStreamController implements CustomerTrackingStreamApi {

    private final TrackingSubscriptionService subscriptionService;

    @Override
    public SseEmitter subscribeTracking(Long deliveryId, AuthenticatedCustomer customer) {
        return subscriptionService.subscribe(deliveryId, customer.memberId());
    }
}
