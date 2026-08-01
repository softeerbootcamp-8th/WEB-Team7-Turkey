package com.turkey.quick.payment.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.payment.domain.PointWallet;
import com.turkey.quick.payment.dto.PointBalanceResponse;
import com.turkey.quick.payment.repository.PointWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 포인트·정산 서비스(RIDE-POINT-002, #67).
 *
 * <p><b>고객과 로직이 같은데 왜 클래스를 나누는가</b>: 지갑 조회 자체는 {@code CustomerPaymentService}
 * 와 두 줄이 같다. 그럼에도 액터별로 나눈 이유는 세션·인터셉터를 고객/라이더가 각자 패키지에
 * 중복해 두기로 한 판단(`CLAUDE.md` 「확인이 필요한 항목」)과 같다 — 아직 존재하지 않는 공통점을
 * 미리 추상화하지 않는다. 게다가 라이더 쪽에는 곧 정산 내역·거래 내역·출금(잔액 선차감 + 원장 기록)이
 * 붙고 그것들은 고객과 전혀 겹치지 않으므로, 지금 공용 클래스를 만들면 곧 다시 갈라야 한다.
 *
 * <p><b>출금 중복 반영을 막는 방법</b>(이슈 비고): 이 조회는 {@code point_wallet.balance} 를 그대로
 * 돌려줄 뿐 진행 중 출금을 빼거나 더하지 않는다. 출금은 요청 시점에 잔액을 <b>선차감</b>하고
 * WITHDRAWAL 원장을 남기며, 송금이 실패하면 WITHDRAWAL_REFUND 로 되돌린다({@code RiderPointApi}).
 * 즉 PENDING 출금은 이미 잔액에서 빠져 있고 실패 출금은 이미 복구되어 있다 — 여기서 다시 보정하면
 * 그게 곧 이중 반영이다. 잔액 계산을 원장 합산으로 다시 하지 않는 이유도 같다.
 */
@Service
@RequiredArgsConstructor
public class RiderPaymentService {

    private final PointWalletRepository pointWalletRepository;

    /**
     * 출금 가능 포인트 잔액 조회(이슈 처리 흐름 ②③).
     *
     * <p>세션 인터셉터가 이미 쿠키 → 세션 → 회원 재조회 → 역할·상태 확인을 마쳤으므로 여기서 회원을
     * 다시 조회하지 않는다. 미로그인·만료·역할 불일치는 이 메서드에 도달하기 전에 401 로 끝난다.
     *
     * <p>지갑은 회원가입 트랜잭션에서 잔액 0 으로 함께 생성된다({@code RiderSignupService}).
     * 따라서 "지갑 없음"은 사용자가 고칠 수 있는 요청 오류가 아니라 서버 데이터 정합성 오류이고,
     * 그래서 4xx 가 아니라 500 으로 낸다({@code RiderPointApi} 문서와 일치).
     * 고객 쪽({@code CustomerPaymentService})은 같은 상황을 {@code IllegalStateException} → 400 으로
     * 내고 있어 지금 두 API 의 동작이 다르다 — 이번 이슈 범위 밖이라 건드리지 않고 미결로 남긴다.
     *
     * @param riderId 세션에서 확인된 라이더 식별자. 요청 파라미터·바디로 받지 않는다.
     * @throws BusinessException 지갑이 없음 (→ 500)
     */
    @Transactional(readOnly = true)
    public PointBalanceResponse getPointBalance(Long riderId) {
        PointWallet wallet = pointWalletRepository.findByMemberId(riderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "포인트 지갑을 찾을 수 없습니다. riderId=" + riderId));

        return new PointBalanceResponse(wallet.getBalance(), wallet.getUpdatedAt());
    }
}
