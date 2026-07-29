package com.turkey.quick.rider.service;

import com.turkey.quick.common.auth.SessionStore;
import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 라이더 로그아웃(이슈 #51). 고객 로그아웃(#28)과 달리 운행 상태를 함께 다뤄야 해서
 * 컨트롤러가 아니라 서비스 계층으로 뺐다.
 *
 * <p>처리 규칙:
 * <ul>
 *   <li>세션 쿠키가 없거나 이미 만료·삭제된 세션이면 아무것도 바꾸지 않고 로그아웃 완료로 본다(멱등, #28과 동일).</li>
 *   <li>BUSY(배송 수행 중)면 로그아웃을 거부한다(409). 세션·상태 어느 것도 건드리지 않아 라이더는 로그인 상태로 남는다.</li>
 *   <li>AVAILABLE이면 UNAVAILABLE로 전이한다. 배차 후보 조회는 AVAILABLE 라이더만 대상으로 하므로
 *       이 전이가 곧 "배차 후보에서 제외"다(#51 계약 확정, 사람 확인). 별도 Redis GEO 후보 스토어는
 *       아직 없어 이 전이 외에 제거할 멤버십이 없다.</li>
 *   <li>UNAVAILABLE이면 이미 오프라인이라 상태는 그대로 두고 세션만 정리한다(로그인은 운행 상태를 바꾸지 않으므로 정상 케이스다).</li>
 * </ul>
 *
 * <p>실패 시 동작: 상태 전이(MySQL)와 세션 삭제(Redis)는 하나의 원자적 커밋으로 묶을 수 없다.
 * 세션을 먼저 지운 뒤 상태 커밋이 실패하면 "로그아웃됐지만 여전히 AVAILABLE(배차 후보)"인 라이더가
 * 남을 수 있다. 그래서 세션 삭제를 트랜잭션 {@code afterCommit}로 미뤄, 상태 전이가 확정된 뒤에만
 * 세션을 지운다. 커밋이 실패하면 세션이 살아있어 라이더가 다시 로그아웃을 시도할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class RiderLogoutService {

    private final SessionStore sessionStore;
    private final RiderProfileRepository riderProfileRepository;

    @Transactional
    public void logout(String sessionId) {
        if (sessionId == null) {
            return; // 쿠키 없음 — 지울 세션도, 바꿀 상태도 없다.
        }

        Optional<Long> memberId = sessionStore.findMemberId(sessionId);
        if (memberId.isEmpty()) {
            return; // 이미 만료·삭제된 세션 — 로그아웃 완료로 처리한다(쿠키 만료는 컨트롤러가 한다).
        }

        RiderProfile profile = riderProfileRepository.findById(memberId.get()).orElseThrow();
        if (profile.getOperatingStatus() == OperatingStatus.BUSY) {
            throw new BusinessException(HttpStatus.CONFLICT, "배송 진행 중에는 로그아웃할 수 없습니다.");
        }
        if (profile.isAvailable()) {
            profile.goOffline(); // AVAILABLE → UNAVAILABLE, 배차 후보에서 제외
        }

        deleteSessionAfterCommit(sessionId);
    }

    /** 상태 전이 커밋이 성공한 뒤에만 세션을 지운다. 트랜잭션 밖에서 호출되면 즉시 지운다. */
    private void deleteSessionAfterCommit(String sessionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sessionStore.delete(sessionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionStore.delete(sessionId);
            }
        });
    }
}
