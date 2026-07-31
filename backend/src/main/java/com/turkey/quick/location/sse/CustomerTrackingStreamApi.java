package com.turkey.quick.location.sse;

import com.turkey.quick.customer.auth.AuthenticatedCustomer;
import com.turkey.quick.customer.auth.CustomerSessionInterceptor;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 고객의 실시간 위치 구독 스트림(#77).
 *
 * <p><b>경로가 액터 우선인 이유</b>: 이 저장소의 관례이고({@code /api/rider/location} 도
 * {@code /api/location/...} 이 아니라 그렇게 골랐다), 형제 엔드포인트인 스냅샷
 * {@code GET .../tracking}(#79)과 같은 자원 아래 놓인다. 무엇보다 <b>고객 API 는
 * {@code CustomerWebMvcConfig} 한 곳에 등록한다</b>는 규칙이 유지된다 — 초안이던
 * {@code /api/location/stream/{orderId}} 를 쓰면 같은 접두사가 고객·라이더 설정 두 곳으로 갈려
 * 등록 누락을 리뷰에서 잡기 어려워진다.
 *
 * <p><b>{@code @Hidden} 으로 OpenAPI 에서 제외한다.</b> SSE 는 Orval 생성 대상이 아니고
 * (프론트 {@code useTrackingStream} 은 손으로 쓴다), 스펙에 노출하면 응답 스키마가
 * {@code SseEmitter} 라는 프레임워크 타입으로 새어 나간다. 계약은 이 javadoc 과
 * {@code docs/04-frontend-api-map.md} 에 적는다. 구현 클래스에도 같은 어노테이션을 달아 뒀다 —
 * springdoc 이 인터페이스가 아니라 핸들러 클래스를 기준으로 볼 수 있어서다.
 *
 * <p><b>스트림 계약</b> (성공 시 200 {@code text/event-stream}):
 * <ul>
 *   <li>{@code event: init} — 연결 직후 1회. {@link TrackingInitPayload}. {@code retry: 3000} 동봉</li>
 *   <li>{@code event: location} — 라이더 위치가 실제로 바뀔 때마다(#78). 위치 스냅샷</li>
 *   <li>{@code :hb} — 주석 프레임. {@code EventSource} 가 무시한다</li>
 * </ul>
 *
 * <p><b>연결 실패는 {@code ApiResponse} 형태의 JSON</b>이다: 401(세션) · 404(없는 주문 또는
 * 타인 주문) · 409(추적 불가 상태) · 429(연결 한도) · 503(저장소 장애).
 *
 * <p>단 <b>브라우저 {@code EventSource} 는 그 상태코드와 본문을 스크립트에 노출하지 않는다</b>
 * ({@code onerror} 만 발생). 그래서 화면은 401 과 409 를 구분할 수 없고, 프론트는 #79 스냅샷 REST
 * 로 판정한 뒤 스트림을 붙여야 한다. 대신 200 이 아닌 응답에는 자동 재연결하지 않으므로
 * 무한 재연결 루프는 생기지 않는다.
 */
@Hidden
@RequestMapping("/api/customer/deliveries")
public interface CustomerTrackingStreamApi {

    /**
     * @param deliveryId 구독할 배송요청. 본인 주문이어야 하고 상태가 {@code ASSIGNED} 이상
     *                   {@code COMPLETED} 이전이어야 한다
     * @param customer   세션 인터셉터가 넣어 준 인증 정보. {@code @RequestAttribute} 는 기본
     *                   required 라, 경로를 인터셉터에 등록하지 않으면 이 API 는 인증 없이 열리는
     *                   대신 {@code ServletRequestBindingException} 으로 아예 동작하지 않는다 —
     *                   조용히 열리는 것보다 나은 실패 방식이다
     */
    @GetMapping(value = "/{deliveryId}/tracking/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribeTracking(
            @PathVariable Long deliveryId,
            @RequestAttribute(CustomerSessionInterceptor.CURRENT_CUSTOMER_ATTRIBUTE)
            AuthenticatedCustomer customer);
}
