package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderOperatingStatusResponse;
import com.turkey.quick.rider.dto.RiderOperatingStatusUpdateRequest;
import com.turkey.quick.rider.service.RiderOperatingStatusQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운행 상태 조회(#53) 구현. 경로·HTTP 메서드 매핑과 바인딩·검증은 payment
 * (`CustomerPointApi`/`RiderPaymentController`) 관례대로 이 구현체에 둔다.
 *
 * <p>{@code changeOperatingStatus}(PATCH)는 이번 이슈(#53, 조회) 범위 밖이라 payment 의 미구현
 * 엔드포인트({@code RiderPaymentController})와 같은 {@code return null} 스텁으로 둔다 — #54 가 채운다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/operating-status")
public class RiderOperatingStatusController implements RiderOperatingStatusApi {

    private final RiderOperatingStatusQueryService riderOperatingStatusQueryService;

    @Override
    @GetMapping
    public ApiResponse<RiderOperatingStatusResponse> getOperatingStatus(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider) {
        return ApiResponse.ok(riderOperatingStatusQueryService.getOperatingStatus(rider.memberId()));
    }

    @Override
    @PatchMapping
    public ApiResponse<RiderOperatingStatusResponse> changeOperatingStatus(
            @Valid @RequestBody RiderOperatingStatusUpdateRequest request) {
        return null; // #54(운행 상태 변경)에서 구현. #53 범위 밖.
    }
}
