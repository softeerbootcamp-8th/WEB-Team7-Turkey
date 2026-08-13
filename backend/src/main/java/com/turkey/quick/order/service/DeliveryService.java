package com.turkey.quick.order.service;

import com.turkey.quick.order.domain.FarePolicy;
import com.turkey.quick.order.domain.FarePolicyStatus;
import com.turkey.quick.order.domain.ItemType;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.order.domain.Address;
import com.turkey.quick.order.domain.Contact;
import com.turkey.quick.order.domain.DeliveryOrder;
import com.turkey.quick.order.domain.FareType;
import com.turkey.quick.order.domain.ItemTypeSurcharge;
import com.turkey.quick.order.domain.OrderFareSnapshot;
import com.turkey.quick.order.domain.OrderStatus;
import com.turkey.quick.order.dto.AddressRequest;
import com.turkey.quick.order.dto.DeliveryCancelResponse;
import com.turkey.quick.order.dto.DeliveryCreateRequest;
import com.turkey.quick.order.dto.DeliveryCreateResponse;
import com.turkey.quick.order.dto.FareBreakdownResponse;
import com.turkey.quick.order.dto.FareQuoteRequest;
import com.turkey.quick.order.dto.FareQuoteResponse;
import com.turkey.quick.order.repository.DeliveryOrderRepository;
import com.turkey.quick.order.repository.FarePolicyRepository;
import com.turkey.quick.order.repository.OrderFareSnapshotRepository;
import com.turkey.quick.location.sse.TrackingPublisher;
import com.turkey.quick.payment.service.CustomerPaymentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class DeliveryService {

    final double EARTH_RADIUS = 6371.0088;
    final int ESTIMATE_MINUTES_CONSTANT = 600;

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private static final BigDecimal METERS_PER_KM = BigDecimal.valueOf(1000);

    private final FarePolicyRepository farePolicyRepository;
    private final DeliveryOrderRepository deliveryOrderRepository;
    private final OrderFareSnapshotRepository orderFareSnapshotRepository;
    private final MemberRepository memberRepository;

    /**
     * 포인트 차감(#40)의 정본.
     *
     * <p><b>의존 방향은 order → payment 로 고정한다.</b> 반대로 payment 가 이 서비스를 주입받으면
     * 여기서 곧바로 스프링 빈 순환이 되어 애플리케이션이 뜨지 않는다(Boot 3.4 는 순환 참조 기본
     * 금지 — 컴파일이 아니라 기동이 실패한다). payment 쪽에서 order 를 참조하는 것은
     * 엔터티({@code PointTransaction.deliveryOrder} 등)까지이고 서비스는 참조하지 않는다.
     */
    private final CustomerPaymentService customerPaymentService;

    /** CANCELED 전이를 그 배송의 SSE 채널에 알린다(#444). 트랜잭션 안에서 부르면 커밋 후로 자동으로 미뤄진다. */
    private final TrackingPublisher trackingPublisher;

    /**
     * 요금 견적. 활성 정책(fare_policy)과 좌표만으로 계산하며 주문·스냅샷은 만들지 않는다.
     * ItemTypeSurcharge 는 지연 로딩이라 정책 조회와 같은 트랜잭션 안에서 접근해야 한다.
     */
    @Transactional(readOnly = true)
    public FareQuoteResponse quoteFare(FareQuoteRequest request) {
        FareCalculation calculation = calculate(
                request.itemType(), request.pickupAddress(), request.destinationAddress());

        return new FareQuoteResponse(
                calculation.fare(), estimateMinutes(calculation.distanceMeters()));
    }

    /**
     * 요금 산정 결과. 견적(#39)은 {@link FareBreakdownResponse} 만 있으면 되지만, 주문 생성(#37)은
     * {@code order_fare_snapshot.fare_policy_id} FK 를 채워야 해서 <b>정책 엔터티까지</b> 필요하다.
     * 그래서 계산 결과를 응답 DTO 가 아니라 이 레코드로 돌려주고, 두 경로가 같은 계산을 공유한다.
     */
    private record FareCalculation(FarePolicy policy, FareBreakdownResponse fare, int distanceMeters) {
    }

    /**
     * 활성 정책(fare_policy)과 좌표만으로 요금을 산정한다. 주문·스냅샷은 만들지 않는다.
     * ItemTypeSurcharge 는 지연 로딩이라 정책 조회와 같은 트랜잭션 안에서 접근해야 한다 —
     * 호출자가 모두 {@code @Transactional} 이라는 전제가 여기에 걸려 있다.
     */
    private FareCalculation calculate(ItemType itemType, AddressRequest pickup,
                                      AddressRequest destination) {
        FarePolicy activePolicy = farePolicyRepository.findByStatus(FarePolicyStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("활성화된 요금 정책이 없습니다."));

        BigDecimal distanceKm = distance(
                pickup.latitude(), pickup.longitude(),
                destination.latitude(), destination.longitude());
        int distanceMeters = toMeters(distanceKm);

        if (distanceMeters > activePolicy.getMaxDeliveryDistanceMeters()) {
            throw new IllegalArgumentException(
                    "최대 배송 거리를 초과했습니다. distanceMeters=" + distanceMeters
                            + ", maxDeliveryDistanceMeters=" + activePolicy.getMaxDeliveryDistanceMeters());
        }

        return new FareCalculation(activePolicy,
                calculateFare(activePolicy, itemType, distanceMeters), distanceMeters);
    }

    /**
     * 배송요청 생성 + 배송요금 포인트 차감(REQ-ORD-002 #37, CUS-PAY-002 #40).
     *
     * <p><b>두 이슈가 한 메서드인 이유</b>: 양쪽 비고가 "하나의 상위 트랜잭션으로 처리하여 주문 생성
     * 또는 포인트 차감 중 하나라도 실패하면 전체 롤백한다"고 명시한다. 나누면 "주문은 생겼는데
     * 결제가 안 된" 상태나 그 반대가 만들어진다. 그래서 {@code @Transactional} 하나가 ④~⑥ 을 덮고,
     * 차감 메서드는 {@code Propagation.MANDATORY} 라 이 트랜잭션 밖에서는 호출 자체가 실패한다.
     *
     * <p><b>이 요청은 커넥션 1개만 쓴다</b>(#463, #446 형제 버그). 지연 만료(#42)의
     * {@code expireIfStale} 는 {@code REQUIRES_NEW} 라, 예전처럼 이 {@code @Transactional} 안에서
     * 부르면 바깥 트랜잭션이 커넥션 C1 을 쥔 채 suspend 되고(suspend 는 반납이 아니다) 새 트랜잭션이
     * C2 를 또 요구해, 생성 1건이 커넥션 2개를 동시 점유한다 — 서로 다른 고객이 동시에 다수 주문을
     * 만들면 풀이 고갈돼 교착된다. 그래서 만료 정리를 이 경계 밖(컨트롤러)으로 옮겨 만료 정리와 생성이
     * 커넥션을 순차로만 쓰게 했다. {@code REQUIRES_NEW} 독립 커밋 의미와 "새 주문 생성이 만료 주문을
     * 정리한다(#42)"는 동작은 그대로다.
     *
     * <p><b>흐름</b>(두 이슈의 처리 흐름을 합친 것)
     *
     * <ol>
     *   <li>멱등 조회 — 같은 {@code requestKey} 의 주문이 이미 있으면 새로 만들지 않고 그 결과를
     *       돌려준다. 이때 포인트도 다시 차감하지 않는다.
     *   <li>서버가 거리·요금 재계산. 클라이언트가 보낸 {@code estimatedFare} 는 <b>대조에만</b> 쓰고
     *       차감 금액은 항상 서버 계산값이다.
     *   <li>주문을 WAITING 으로 저장.
     *   <li>ESTIMATE 운임 스냅샷 저장.
     *   <li>포인트 차감 + ORDER_USE 원장 기록.
     * </ol>
     *
     * <p><b>중복·동시성은 DB 제약이 판정한다.</b> 앱에서 미리 세는 대신 제약 위반을 응답 코드로
     * 바꾼다 — 조회 후 판단하면 그 사이에 다른 요청이 끼어드는 창이 남기 때문이다.
     *
     * <ul>
     *   <li>{@code uk_delivery_customer_request} — 같은 고객·같은 요청키로 주문 2건
     *   <li>{@code uk_delivery_active_customer} — 고객당 진행 중(WAITING~DELIVERING) 1건
     *   <li>{@code uk_point_transaction_order_type} — 같은 주문에 ORDER_USE 두 번(이중 차감)
     * </ul>
     *
     * <p><b>앞의 둘은 같은 INSERT 에서 터져 여기서는 구분되지 않는다.</b> 제약 위반을 잡지 않고 그대로
     * 올려 보내면 {@link DeliveryOrderCreator} 가 재조회 결과로 가른다 — 요청키의 주문이 있으면
     * 멱등키 충돌, 없으면 진행 중 주문 제약이다. DB 벤더 문자열을 파싱하지 않는 방법이다.
     *
     * <p><b>이 메서드를 직접 부르지 말 것.</b> 멱등 보장은 {@code DeliveryOrderCreator} 가 하고,
     * 여기는 그 안쪽 트랜잭션 구간이다({@code PointChargeApprover} 와 같은 역할 분담). 컨트롤러가
     * 이 메서드를 바로 부르면 동시 재전송이 500 으로 새어 나간다.
     *
     * <p><b>잠금 순서</b>: 주문 INSERT → 지갑 잠금. 포인트 트랜잭션의 잠금 순서 규칙
     * (CLAUDE.md 의 {@code point_charge} → {@code point_wallet})과 같은 결이다 — 소스 행을 먼저
     * 잡고 지갑을 마지막에 잡는다. 지갑 잠금 보유 구간이 트랜잭션 끝까지이므로, 그 앞의 작업
     * (요금 계산·주문 저장)을 먼저 끝내 보유 시간을 줄인다.
     *
     * @param request    배송요청 정보. {@code estimatedFare} 는 대조용이다.
     * @param customerId 세션에서 확인된 고객 식별자. 요청 바디로 받지 않는다.
     * @throws IllegalArgumentException 최대 배송 거리 초과 (→ 400)
     * @throws IllegalStateException    활성 요금 정책 없음 · 지갑 없음 (→ 400)
     * @throws BusinessException        409 진행 중 요청 존재·동시 재전송 / 409 요금 변경 /
     *                                  402 포인트 부족
     */
    @Transactional
    public DeliveryCreateResponse createDelivery(DeliveryCreateRequest request, Long customerId) {
        // 지연 만료(#42)는 이 메서드가 아니라 호출자(CustomerDeliveryController)가 이 트랜잭션 밖에서
        // 먼저 수행한다 — 이유는 클래스/메서드 javadoc의 "커넥션 1개" 항목(#463) 참조.

        // ① 멱등: 같은 요청키의 주문이 이미 있으면 그 결과를 그대로 돌려준다(순차 재전송).
        //    포인트도 다시 차감하지 않는다 — 아래 저장 경로를 아예 타지 않기 때문이다.
        Optional<DeliveryOrder> alreadyCreated =
                deliveryOrderRepository.findByCustomer_IdAndRequestKey(customerId, request.requestKey());
        if (alreadyCreated.isPresent()) {
            return toResponse(alreadyCreated.get());
        }

        // ② 요금은 서버가 다시 계산한다. 요청의 estimatedFare 는 대조에만 쓴다.
        FareCalculation calculation =
                calculate(request.itemType(), request.pickup(), request.destination());
        verifyFareUnchanged(request.estimatedFare(), calculation.fare().totalFare());

        // 세션 인터셉터가 이미 회원 존재·역할·상태를 확인했으므로 다시 조회하지 않는다.
        // FK 를 채우는 것이 목적이라 프록시 참조로 충분하다(chargePointRequest 와 같은 판단).
        Member customer = memberRepository.getReferenceById(customerId);

        DeliveryOrder order = DeliveryOrder.request(
                customer,
                request.requestKey(),
                request.itemType(),
                calculation.distanceMeters(),
                toAddress(request.pickup()),
                toAddress(request.destination()),
                Contact.of(request.sender().name(), request.sender().phoneNumber()),
                Contact.of(request.recipient().name(), request.recipient().phoneNumber()));

        // save 가 아니라 saveAndFlush 다: 지연 flush 로는 유니크 위반이 커밋 시점에 터져
        // DeliveryOrderCreator 의 catch 를 지나쳐 500 으로 새어 나간다(#32 에서 확인한 것과 같은 이유).
        //
        // 여기서 DataIntegrityViolationException 을 잡지 않는다 — 잡아서 BusinessException 으로
        // 바꾸면 DeliveryOrderCreator 의 catch(DataIntegrityViolationException) 를 지나쳐 버려
        // 재조회 경로를 못 타고 "동시 요청은 무조건 409" 이던 예전 동작으로 되돌아간다.
        DeliveryOrder saved = deliveryOrderRepository.saveAndFlush(order);

        // ④ 주문 시점의 요금을 ESTIMATE 스냅샷으로 고정한다. 이후 정책이 바뀌어도 이 주문의
        //    청구 근거는 여기에 남는다(라이더 정산도 이 스냅샷을 참조한다).
        FareBreakdownResponse fare = calculation.fare();
        orderFareSnapshotRepository.save(OrderFareSnapshot.create(
                saved, calculation.policy(), FareType.ESTIMATE,
                fare.policyVersion(), fare.calculationDistanceMeters(),
                fare.baseFare(), fare.distanceFare(), fare.itemSurcharge()));

        // ⑤ 포인트 차감 + ORDER_USE 원장. 실패하면 위 주문·스냅샷까지 함께 롤백된다.
        long balanceAfter = customerPaymentService.payForOrder(
                customerId, saved, fare.totalFare(), request.requestKey());

        log.info("[배송요청-생성] deliveryId={}, customerId={}, totalFare={}, balanceAfter={}",
                saved.getId(), customerId, fare.totalFare(), balanceAfter);

        return toResponse(saved, fare);
    }

    /**
     * 동시 재전송에서 진 요청의 복구 조회({@link DeliveryOrderCreator} 전용).
     * {@link #createDelivery} 가 롤백된 <b>뒤에</b> 불러야 한다.
     */
    @Transactional(readOnly = true)
    public Optional<DeliveryCreateResponse> findCreatedByRequestKey(Long customerId, String requestKey) {
        return deliveryOrderRepository.findByCustomer_IdAndRequestKey(customerId, requestKey)
                .map(this::toResponse);
    }

    /**
     * 배송요청 취소(#47, CUS-DELIVERY-005). 배차 전(WAITING) 주문만 취소할 수 있다.
     *
     * <p><b>동작 순서</b>
     * <ol>
     *   <li>소유권 확인({@code findByIdAndCustomer_Id}) — 없거나 타인 것이면 404(#46 과 같은
     *       이유로 사유를 구분하지 않는다 — 존재 여부를 노출하지 않는다).</li>
     *   <li>이미 CANCELED 면 그 결과를 그대로 돌려주고 끝낸다 — 중복 취소·중복 환급을 막는 멱등
     *       처리다(#34 {@code cancelPointCharge} 와 같은 판단).</li>
     *   <li>그 외 상태면 조건부 UPDATE({@link DeliveryOrderRepository#cancelIfWaiting}, ADR-006
     *       패턴 — #42/#56 이 이미 쓰는 메서드를 그대로 재사용한다)로 WAITING → CANCELED 전이를
     *       시도한다. 배차 확정(#56)과 동시에 들어와도 조건부 UPDATE 가 하나만 통과시킨다.</li>
     *   <li>0 행이면 {@link #handleLostRace} 로 넘겨 사유를 구분한다(#405).</li>
     *   <li>1 행이면 예상 운임(ESTIMATE) 스냅샷 금액만큼 환급한다
     *       ({@link CustomerPaymentService#refundForCancel}).</li>
     * </ol>
     *
     * <p>취소 직후 응답은 <b>재조회하지 않고</b> 방금 만든 값(canceledAt)으로 직접 구성한다 — 같은
     * 세션에서 재조회하면 Hibernate 1차 캐시가 갱신 전 인스턴스를 그대로 돌려주는 문제를
     * {@code DeliveryDetailQueryService} 를 만들며 실측으로 확인했다(#46).
     *
     * @param deliveryId 취소할 배송요청
     * @param customerId 세션에서 얻은 고객 식별자
     * @param reason     취소 사유(선택, 없으면 null)
     * @return 취소 결과(성공 시 CANCELED, 이미 취소돼 있었으면 그 시각 그대로)
     * @throws BusinessException     404(없음·타인 것) / 409(배차 확정됐거나 이미 완료돼 취소 불가) /
     *                               500(운임 스냅샷 없음 — 데이터 불변식 위반)
     */
    @Transactional
    public DeliveryCancelResponse cancelDelivery(Long deliveryId, Long customerId, String reason) {
        DeliveryOrder order = deliveryOrderRepository.findByIdAndCustomer_Id(deliveryId, customerId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "배송요청을 찾을 수 없습니다."));

        if (order.getStatus() == OrderStatus.CANCELED) {
            return new DeliveryCancelResponse(order.getId(), order.getStatus(), order.getCanceledAt());
        }

        LocalDateTime canceledAt = LocalDateTime.now(ZoneOffset.UTC);
        int updated = deliveryOrderRepository.cancelIfWaiting(deliveryId, canceledAt, reason);
        if (updated == 0) {
            return handleLostRace(deliveryId);
        }

        OrderFareSnapshot estimate = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(deliveryId, FareType.ESTIMATE)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "배송요청에 예상 운임 스냅샷이 없습니다. deliveryId=" + deliveryId));

        long balanceAfter = customerPaymentService.refundForCancel(
                customerId, deliveryOrderRepository.getReferenceById(deliveryId), estimate.getTotalFare());

        trackingPublisher.publishStatus(deliveryId, OrderStatus.CANCELED, canceledAt.toInstant(ZoneOffset.UTC));

        log.info("[배송요청-취소] deliveryId={}, customerId={}, refundAmount={}, balanceAfter={}",
                deliveryId, customerId, estimate.getTotalFare(), balanceAfter);

        return new DeliveryCancelResponse(deliveryId, OrderStatus.CANCELED, canceledAt);
    }

    /**
     * {@link #cancelDelivery} 의 조건부 UPDATE 가 0 행일 때 사유를 구분한다(#405).
     *
     * <p>도착 순서에 따라 결과가 갈리던 문제를 없애는 지점이다: 취소 요청 두 개가 거의 동시에
     * 253 라인의 최초 조회를 통과하면, 둘 다 아직 CANCELED 를 못 보고 조건부 UPDATE 로 넘어간다.
     * 여기서 진 요청이 곧바로 409 를 던지는 대신, {@link DeliveryOrderRepository#findByIdForShare}
     * 로 다시 확인해 <b>같은 취소끼리 경쟁해 진 것</b>과 <b>배차 확정 등 다른 전이와 경쟁해 진 것</b>을
     * 구분한다.
     *
     * <p>평범한 조회(일관된 읽기)가 아니라 잠금 읽기를 쓰는 이유: 이 트랜잭션은 방금
     * {@code cancelIfWaiting} 으로 이 행을 검사했고, REPEATABLE READ 에서는 조건에 안 맞아
     * 갱신하지 못했어도 검사한 행의 락은 남는다. 평범한 재조회는 이 트랜잭션이 시작할 때 고정된
     * 스냅샷을 그대로 따르므로 방금 상대가 커밋한 값을 못 볼 수 있는 반면, 잠금 읽기는 스냅샷을
     * 건너뛰어 항상 최신 커밋을 본다 — 그리고 이미 쥔 락이라 대기 없이 통과한다.
     *
     * @return CANCELED 로 확인되면 그 시각으로 200(멱등)
     * @throws BusinessException 409(다른 상태로 전이됨) — CANCELED 가 아닌 모든 경우
     */
    private DeliveryCancelResponse handleLostRace(Long deliveryId) {
        DeliveryOrder current = deliveryOrderRepository.findByIdForShare(deliveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "방금 조건부 UPDATE를 시도한 주문을 재조회할 수 없습니다. deliveryId=" + deliveryId));

        if (current.getStatus() == OrderStatus.CANCELED) {
            return new DeliveryCancelResponse(current.getId(), current.getStatus(), current.getCanceledAt());
        }

        throw new BusinessException(HttpStatus.CONFLICT,
                "이미 배차되었거나 완료되어 취소할 수 없습니다. deliveryId=" + deliveryId);
    }

    /**
     * 화면이 안내한 금액과 서버 재계산 금액을 대조한다(#37 예외 처리).
     *
     * <p><b>다르면 주문을 만들지 않는다</b>(사람 확인). 5,000원을 보고 결제 버튼을 눌렀는데
     * 5,500원이 빠져나가는 것을 막기 위해서다. 서버 값으로 조용히 진행하는 선택지도 있었지만
     * 그건 사용자가 동의하지 않은 금액을 결제하는 것이 된다.
     *
     * <p>400 이 아니라 409 인 이유: 요청 자체는 형식·값 모두 유효하고, 어긋난 것은 그 사이에 바뀐
     * <b>서버의 요금 정책</b>이다. 이 엔드포인트의 400 은 Bean Validation 오류로 이미 많이 나가는데,
     * 요금 변경은 사용자가 입력을 고쳐서 해결할 수 있는 종류가 아니라 화면이 요금을 다시 받아
     * 보여줘야 하는 상황이라 섞이면 안 된다.
     */
    private void verifyFareUnchanged(long requestedFare, long serverFare) {
        if (requestedFare != serverFare) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "배송요금이 변경되었습니다. 요금을 다시 확인해 주세요. 요청=%d, 서버=%d"
                            .formatted(requestedFare, serverFare));
        }
    }

    private Address toAddress(AddressRequest request) {
        return Address.of(request.roadAddress(), request.detailAddress(), request.postalCode(),
                request.latitude(), request.longitude());
    }

    /**
     * 멱등 재전송의 응답. 저장된 ESTIMATE 스냅샷에서 요금을 되읽는다 — 지금 다시 계산하면
     * 정책이 바뀐 뒤에는 <b>실제 청구된 금액과 다른 값</b>을 돌려주게 된다.
     */
    private DeliveryCreateResponse toResponse(DeliveryOrder order) {
        OrderFareSnapshot snapshot = orderFareSnapshotRepository
                .findByOrder_IdAndFareType(order.getId(), FareType.ESTIMATE)
                .orElseThrow(() -> new IllegalStateException(
                        "주문에 예상 운임 스냅샷이 없습니다. deliveryId=" + order.getId()));

        return toResponse(order, FareBreakdownResponse.from(snapshot));
    }

    private DeliveryCreateResponse toResponse(DeliveryOrder order, FareBreakdownResponse fare) {
        return new DeliveryCreateResponse(
                order.getId(), order.getStatus(), order.getRequestKey(), fare, order.getRequestedAt());
    }

    public int estimateMinutes(int distanceMeters) {
        return (int) Math.ceil((double) distanceMeters / ESTIMATE_MINUTES_CONSTANT);
    }

    /**
     * base + distance + surcharge 를 계산한다. total 은 항상 세 값의 합으로 파생시켜
     * {@link com.turkey.quick.order.domain.OrderFareSnapshot} 의 ck_order_fare_total 불변식과
     * 어긋나지 않게 한다.
     *
     * <p>거리 운임은 distanceUnitMeters 로 나눈 몫을 올림해 단위 수를 구한다(부분 구간도 한 단위로
     * 청구) — 택시 미터기와 같은 방식이다. 해당 물품 종류에 등록된 할증이 없으면 0으로 취급한다
     * (모든 ItemType 이 할증을 가질 필요는 없다).
     */
    private FareBreakdownResponse calculateFare(FarePolicy policy, ItemType itemType, int distanceMeters) {
        long baseFare = policy.getBaseFare();
        long distanceUnits = Math.ceilDiv(distanceMeters, policy.getDistanceUnitMeters());
        long distanceFare = distanceUnits * policy.getDistanceUnitFare();
        long itemSurcharge = policy.getSurcharges().stream()
                .filter(surcharge -> surcharge.getItemType() == itemType)
                .findFirst()
                .map(ItemTypeSurcharge::getSurchargeAmount)
                .orElse(0L);
        long totalFare = baseFare + distanceFare + itemSurcharge;

        return new FareBreakdownResponse(
                policy.getPolicyVersion(), distanceMeters, baseFare, distanceFare, itemSurcharge, totalFare);
    }

    /**
     * km(BigDecimal) 을 정수 미터로 반올림한다. Address.normalize 와 동일하게 HALF_UP 을 쓴다
     * (이 저장소의 좌표·거리 반올림 관례).
     */
    private int toMeters(BigDecimal distanceKm) {
        return distanceKm.multiply(METERS_PER_KM).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }


    /**
     * 두 좌표 간의 지구 표면 기준 직선(구면) 거리를 계산합니다.
     *
     * <p><strong>사용주의 사항</strong></p>
     * <ul>
     *   <li><strong>하버사인 공식(Haversine Formula) 적용:</strong> 평면상의 단순 피타고라스 직선거리는
     *       지구가 구체라는 공간적 특성을 반영하지 못해 실제 거리와 오차가 심하므로 구면 기하학을 고려한 공식을 적용했습니다.</li>
     *   <li><strong>내부 double 변환 사유:</strong> 자바 표준 {@link java.lang.Math} 라이브러리의 삼각함수
     *       메서드들이 {@code double} 타입만을 지원하기 때문에 내부 연산 시 일시적으로 형변환을 수행.</li>
     *   <li><strong>오차 정밀도 검토:</strong> 본 시스템은 단순 직선거리 기반의 대략적인 운임 측정을 목적으로 하므로,
     *       부동소수점({@code double}) 변환 과정에서 발생하는 미세한 연산 오차는 비즈니스 레이어에서 크리티컬하지 않다고 생각</li>
     * </ul>
     *
     * @param latitude1  출발지 위도 (BigDecimal)
     * @param longitude1 출발지 경도 (BigDecimal)
     * @param latitude2  도착지 위도 (BigDecimal)
     * @param longitude2 도착지 경도 (BigDecimal)
     * @return 하버사인 공식을 통해 산정된 최종 직선거리 (단위: km, 소수점 3자리 반올림)
     */
    public BigDecimal distance(BigDecimal latitude1, BigDecimal longitude1, BigDecimal latitude2, BigDecimal longitude2) {
        if (latitude1 == null || longitude1 == null || latitude2 == null || longitude2 == null) {
            throw new IllegalArgumentException("좌표 값은 필수여야 합니다.");
        }

        double lat1Rad = Math.toRadians(latitude1.doubleValue());
        double lon1Rad = Math.toRadians(longitude1.doubleValue());
        double lat2Rad = Math.toRadians(latitude2.doubleValue());
        double lon2Rad = Math.toRadians(longitude2.doubleValue());

        double deltaLat = lat2Rad - lat1Rad;
        double deltaLon = lon2Rad - lon1Rad;

        // 하버사인 연산
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                    Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                    Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double distanceKm = EARTH_RADIUS * c;

        return BigDecimal.valueOf(distanceKm).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * 반경(m)을 감싸는 사각형(bounding box) 좌표 범위를 계산한다(#367, 라이더 콜 목록 위치 검색).
     * 위도 1도가 감싸는 거리는 {@link #EARTH_RADIUS}(지구 반지름, km) 기준 라디안 환산으로
     * 구하고, 경도는 위도에 따라 보정한다(고위도일수록 경도 1도의 실제 거리가 짧아지므로
     * {@code cos(latitude)}로 나눈다) — {@link #distance} 와 같은 구면 기하 전제를 쓴다
     * (하드코딩된 위도별 상수를 쓰던 {@link #pureStraightDistance} 와 달리, 위도에 무관하게
     * 정확하다).
     *
     * <p>사각형은 원보다 넓다(4/π ≈ 1.27배) — 이 범위로 뽑힌 후보에는 실제 반경 밖인 것이
     * 섞여 있을 수 있다. 호출자가 {@link #distance} 로 다시 걸러야 한다(2단계 필터링).
     */
    public BoundingBox boundingBox(BigDecimal latitude, BigDecimal longitude, int radiusMeters) {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("좌표 값은 필수여야 합니다.");
        }

        double radiusKm = radiusMeters / 1000.0;
        double latDeltaDeg = Math.toDegrees(radiusKm / EARTH_RADIUS);
        double lngDeltaDeg = Math.toDegrees(
                radiusKm / (EARTH_RADIUS * Math.cos(Math.toRadians(latitude.doubleValue()))));

        BigDecimal latDelta = BigDecimal.valueOf(latDeltaDeg);
        BigDecimal lngDelta = BigDecimal.valueOf(lngDeltaDeg);

        return new BoundingBox(
                latitude.subtract(latDelta),
                latitude.add(latDelta),
                longitude.subtract(lngDelta),
                longitude.add(lngDelta));
    }

    /** {@link #boundingBox} 결과값 — bounding box 쿼리에 그대로 넘길 좌표 범위(#367). */
    public record BoundingBox(BigDecimal latMin, BigDecimal latMax, BigDecimal lngMin, BigDecimal lngMax) {}


    @Deprecated
    public BigDecimal pureStraightDistance(BigDecimal latitude1, BigDecimal longitude1, BigDecimal latitude2, BigDecimal longitude2) {
        if (latitude1 == null || longitude1 == null || latitude2 == null || longitude2 == null) {
            throw new IllegalArgumentException("좌표 값은 필수여야 합니다.");
        }

        // *** 대한민국 위도(약 37도) 기준 1도당 실제 거리 상수 km 단위
        // 위도 1도 = 약 111km, 경도 1도 = 약 88.5km (위도에 따라 가변적이나 평면 가정 선언)
        final double KM_PER_LATITUDE = 111.0;
        final double KM_PER_LONGITUDE = 88.5;

        double lat1 = latitude1.doubleValue();
        double lon1 = longitude1.doubleValue();
        double lat2 = latitude2.doubleValue();
        double lon2 = longitude2.doubleValue();

        // 2. 위·경도 차이를 km 단위 거리로 환산
        double x = (lon2 - lon1) * KM_PER_LONGITUDE;
        double y = (lat2 - lat1) * KM_PER_LATITUDE;

        // 3. 피타고라스 공식
        double distanceKm = Math.sqrt((x * x) + (y * y));

        return BigDecimal.valueOf(distanceKm).setScale(8, RoundingMode.HALF_UP);
    }
}
