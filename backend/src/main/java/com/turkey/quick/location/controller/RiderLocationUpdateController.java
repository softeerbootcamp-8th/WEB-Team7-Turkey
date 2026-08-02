package com.turkey.quick.location.controller;

import com.turkey.quick.common.exception.BusinessException;
import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import com.turkey.quick.location.sse.SseRelay;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.domain.OperatingStatus;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RiderLocationUpdateController implements RiderLocationUpdateApi {

    // 허용 목록으로 두는 이유: 나중에 상태가 하나 늘었을 때 거부가 아니라 허용으로 조용히
    // 새는 쪽이 더 위험하다(RiderLocationService의 옛 판단과 동일).
    private static final Set<OperatingStatus> LOCATION_ALLOWED_STATUSES =
            EnumSet.of(OperatingStatus.AVAILABLE, OperatingStatus.BUSY);

    private final SseRelay sseRelay;

    @Override
    public ApiResponse<Void> updateRiderLocation(RiderLocationUpdateRequest request, AuthenticatedRider rider) {
        if (!LOCATION_ALLOWED_STATUSES.contains(rider.operatingStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "운행 중이 아닙니다.");
        }
        sseRelay.publish(request.deliveryId(), request.toLocationPayload());
        return ApiResponse.ok(null);
    }
}
