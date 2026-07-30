package com.turkey.quick.location.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.dto.RiderLocationUpdateResponse;
import com.turkey.quick.location.service.RiderLocationService;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RiderLocationController implements RiderLocationApi {

    private final RiderLocationService riderLocationService;

    /**
     * 인증 record 를 그대로 넘기지 않고 필요한 두 값만 푸는 이유: 그러면 location 도메인이
     * rider/auth 패키지에 의존하지 않고, 서비스 단위 테스트에서 인증 record 를 만들 필요도 없다.
     */
    @Override
    public ApiResponse<RiderLocationUpdateResponse> updateRiderLocation(
            RiderLocationUpdateRequest request, AuthenticatedRider rider) {
        return ApiResponse.ok(
                riderLocationService.update(rider.memberId(), rider.operatingStatus(), request));
    }
}
