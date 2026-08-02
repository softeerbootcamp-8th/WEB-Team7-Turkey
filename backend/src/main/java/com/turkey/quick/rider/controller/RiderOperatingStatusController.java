package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderOperatingStatusResponse;
import com.turkey.quick.rider.dto.RiderOperatingStatusUpdateRequest;
import com.turkey.quick.rider.service.RiderOperatingStatusChangeService;
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
 * 운행 상태 조회(#53)·변경(#54) 구현. 경로·HTTP 메서드 매핑과 바인딩·검증은 payment
 * (`CustomerPointApi`/`RiderPaymentController`) 관례대로 이 구현체에 둔다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/operating-status")
public class RiderOperatingStatusController implements RiderOperatingStatusApi {

    private final RiderOperatingStatusQueryService riderOperatingStatusQueryService;
    private final RiderOperatingStatusChangeService riderOperatingStatusChangeService;

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
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE)
            AuthenticatedRider rider,
            @Valid @RequestBody RiderOperatingStatusUpdateRequest request) {
        return ApiResponse.ok(
                riderOperatingStatusChangeService.changeOperatingStatus(rider.memberId(), request.action()));
    }
}
