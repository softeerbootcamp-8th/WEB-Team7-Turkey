package com.turkey.quick.payment.service;

import com.turkey.quick.member.domain.Member;
import com.turkey.quick.member.repository.MemberRepository;
import com.turkey.quick.payment.domain.PointCharge;
import com.turkey.quick.payment.dto.PointChargeRequest;
import com.turkey.quick.payment.dto.PointChargeResponse;
import com.turkey.quick.payment.repository.PointChargeRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 충전 준비의 <b>DB 트랜잭션 구간</b>만 담당한다(#32). {@link PointChargeApprover} 와 같은 이유로
 * 별도 빈이다 — 같은 클래스 안에서 메서드를 나누면 self-invocation 이라
 * {@code @Transactional} 프록시를 타지 않는다.
 *
 * <p>메서드가 둘로 나뉜 것은 <b>서로 다른 트랜잭션이어야 하기 때문</b>이다. 같은 멱등키가 진짜 동시에
 * 들어오면 두 요청이 모두 "기존 건 없음"으로 판단하고 INSERT 를 시도하고, 늦은 쪽이
 * {@code uk_point_charge_customer_request} 위반을 맞는다. 그 시점의 트랜잭션은 이미 rollback-only 라
 * <b>그 안에서는</b> 이긴 건을 다시 조회해 돌려줄 수 없다 — 커밋 때
 * {@code UnexpectedRollbackException} 으로 다시 터진다. 그래서 {@link #createPending} 이 롤백을
 * 끝낸 뒤 {@link #findByRequestKey} 가 새 트랜잭션으로 이긴 건을 읽는다.
 *
 * <p>재조회가 반드시 찾는다는 보장은 DB 가 준다. 늦은 INSERT 는 앞선 트랜잭션이 커밋할 때까지
 * 유니크 인덱스에서 대기하다가 커밋된 뒤에야 duplicate key 로 실패하므로, 예외를 받은 시점에는
 * 이긴 행이 이미 커밋돼 있다. 앞선 쪽이 롤백했다면 애초에 예외가 나지 않는다.
 */
@RequiredArgsConstructor
@Component
public class PointChargePreparer {

    private final PointChargeRepository pointChargeRepository;
    private final MemberRepository memberRepository;

    /**
     * 멱등키로 기존 요청을 확인하고, 없으면 {@code point_charge} 를 PENDING 으로 저장한다.
     *
     * <p>선조회는 <b>순차</b> 재전송을 흡수한다(INSERT 를 시도조차 하지 않는다). 동시 재전송은 여기서
     * 걸러지지 않고 유니크 제약이 판정하며, 그 예외는 잡지 않고 호출자
     * ({@link CustomerPaymentService#chargePointRequest})까지 올려 보낸다.
     */
    @Transactional
    public PointChargeResponse createPending(PointChargeRequest request, Long customerId) {
        Optional<PointCharge> alreadyRequested = pointChargeRepository
                .findByCustomer_IdAndChargeRequestKey(customerId, request.chargeRequestKey());
        if (alreadyRequested.isPresent()) {
            return PointChargeResponse.from(alreadyRequested.get());
        }

        // 세션 인터셉터가 이미 회원 존재·역할·상태를 확인했으므로 여기서 다시 조회하지 않는다.
        // FK 를 채우는 것이 목적이라 프록시 참조로 충분하고, 실제 존재 여부는 FK 제약이 보증한다.
        Member customer = memberRepository.getReferenceById(customerId);

        PointCharge pointCharge = PointCharge.request(
                customer,
                request.chargeRequestKey(),
                request.paymentMethod(),
                request.amount(),
                MockPaymentGateway.PROVIDER);

        // save 가 아니라 saveAndFlush 다: 지연 flush 로는 유니크 위반이 커밋 시점에 터져
        // 호출자의 catch 를 지나쳐 500 으로 새어 나간다.
        return PointChargeResponse.from(pointChargeRepository.saveAndFlush(pointCharge));
    }

    /** 동시 재전송에서 진 요청의 복구 조회. {@link #createPending} 이 롤백된 <b>뒤에</b> 불러야 한다. */
    @Transactional(readOnly = true)
    public Optional<PointChargeResponse> findByRequestKey(Long customerId, String chargeRequestKey) {
        return pointChargeRepository
                .findByCustomer_IdAndChargeRequestKey(customerId, chargeRequestKey)
                .map(PointChargeResponse::from);
    }
}
