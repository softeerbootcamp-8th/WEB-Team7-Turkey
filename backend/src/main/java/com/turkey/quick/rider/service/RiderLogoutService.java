package com.turkey.quick.rider.service;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.rider.domain.OperatingStatus;
import com.turkey.quick.rider.domain.RiderProfile;
import com.turkey.quick.rider.repository.RiderProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 로그아웃(이슈 #51) 중 **운행 상태 처리**만 담당한다.
 * 세션 삭제·쿠키 만료는 고객 로그아웃(#28)과 동일하게 컨트롤러가 하고, 여기서는 DB 상태 전이만 다룬다.
 *
 * <p>상태별 규칙(대상 라이더는 memberId로 이미 특정된 상태로 넘어온다):
 * <ul>
 *   <li>BUSY(배송 수행 중) → 로그아웃 거부(409). 상태를 바꾸지 않고 예외를 던진다.</li>
 *   <li>AVAILABLE → UNAVAILABLE로 전이한다. 배차 후보 조회는 AVAILABLE 라이더만 대상으로 하므로
 *       이 전이가 곧 "배차 후보에서 제외"다(#51 계약 확정, 사람 확인). 별도 Redis GEO 후보 스토어는
 *       아직 없어 이 전이 외에 제거할 멤버십이 없다.</li>
 *   <li>UNAVAILABLE → 이미 오프라인이라 그대로 둔다(로그인은 운행 상태를 바꾸지 않으므로 정상 케이스).</li>
 * </ul>
 *
 * <p>세션 삭제(Redis)는 이 트랜잭션이 커밋된 뒤에 일어나야 한다 — 그래야 커밋 실패 시 "로그아웃됐지만
 * 여전히 AVAILABLE(배차 후보)"인 라이더가 남는 구멍을 피한다. 그 순서는 컨트롤러가 보장한다:
 * 이 {@code @Transactional} 메서드가 정상 리턴하면 커밋이 끝난 상태이므로 컨트롤러가 그 뒤에 세션을 지우고,
 * BUSY로 예외가 나면 컨트롤러의 세션 삭제 지점에 도달하지 않아 세션이 유지된다.
 */
@Service
@RequiredArgsConstructor
public class RiderLogoutService {

    private final RiderProfileRepository riderProfileRepository;

    @Transactional
    public void changeStatusForLogout(Long memberId) {
        RiderProfile profile = riderProfileRepository.findById(memberId).orElseThrow();
        if (profile.getOperatingStatus() == OperatingStatus.BUSY) {
            throw new BusinessException(HttpStatus.CONFLICT, "배송 진행 중에는 로그아웃할 수 없습니다.");
        }
        if (profile.isAvailable()) {
            profile.goOffline(); // AVAILABLE → UNAVAILABLE, 배차 후보에서 제외
        }
    }
}
