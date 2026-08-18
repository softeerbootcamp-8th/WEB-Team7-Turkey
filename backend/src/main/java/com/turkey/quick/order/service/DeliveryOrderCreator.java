package com.turkey.quick.order.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.order.dto.DeliveryCreateRequest;
import com.turkey.quick.order.dto.DeliveryCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 배송요청 생성의 <b>멱등 보장</b>만 담당하는 파사드(#37/#40). 트랜잭션 구간은
 * {@link DeliveryService#createDelivery} 가 갖고 있고, 이 빈은 트랜잭션 밖에서 그 결과를 받는다.
 *
 * <p><b>왜 별도 빈인가.</b> 유니크 위반이 난 트랜잭션은 그 시점에 rollback-only 로 마킹되어, 같은
 * 트랜잭션 안에서 이긴 주문을 다시 조회해 돌려줘 봐야 커밋 때 {@code UnexpectedRollbackException}
 * 으로 다시 터진다. 재조회는 <b>실패한 트랜잭션이 완전히 롤백된 뒤</b> 새 트랜잭션에서 해야 하고,
 * 같은 클래스 안에서 메서드를 나누면 self-invocation 이라 프록시를 안 타 그 경계가 생기지 않는다.
 * {@code PointChargeApprover} 와 같은 이유의 분리다.
 *
 * <p><b>방향이 결제 쪽과 반대인 이유.</b> 충전({@code PointChargePreparer})·출금
 * ({@code WithdrawalRequester})은 <b>안쪽</b>을 새 빈으로 뺐지만 여기서는 <b>바깥쪽</b>을 뺐다.
 * 생성 본문이 {@code DeliveryService} 의 요금 계산({@code calculate}·{@code distance}·
 * {@code calculateFare})에 의존하는데, 본문만 새 빈으로 옮기면 그 빈이 다시
 * {@code DeliveryService} 를 주입받아야 해서 스프링 빈 순환이 된다 — Boot 3.4 는 순환 참조가 기본
 * 금지라 컴파일이 아니라 기동이 실패한다. 요금 계산을 별도 빈으로 먼저 분리하면 방향을 맞출 수
 * 있으나 이번 변경 범위 밖이다.
 */
@RequiredArgsConstructor
@Component
public class DeliveryOrderCreator {

    private final DeliveryService deliveryService;

    /**
     * 배송요청 생성. 같은 {@code requestKey} 는 순차든 동시든 같은 주문을 돌려주고, 포인트는 한 번만
     * 빠진다 — 진 요청은 주문·스냅샷·차감이 통째로 롤백된 뒤 이긴 주문을 재조회해 받는다.
     *
     * <p>재조회로도 못 찾았다면 터진 제약은 멱등키({@code uk_delivery_customer_request})가 아니라
     * 고객당 진행 중 1건({@code uk_delivery_active_customer})이다. 그 둘은 같은 INSERT 에서 터져
     * 예외만으로는 구분되지 않는데, 재조회 결과가 대신 갈라 준다.
     *
     * @throws BusinessException 409 진행 중 배송요청 존재 / 409 요금 변경 / 402 포인트 부족
     */
    public DeliveryCreateResponse create(DeliveryCreateRequest request, Long customerId) {
        try {
            return deliveryService.createDelivery(request, customerId);
        } catch (DataIntegrityViolationException e) {
            // 여기 도달했다 = 위 트랜잭션이 롤백을 끝냈다. 그래서 새 트랜잭션으로 안전하게 재조회한다.
            return deliveryService.findCreatedByRequestKey(customerId, request.requestKey())
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.CONFLICT, "이미 진행 중인 배송요청이 있습니다."));
        }
    }
}
