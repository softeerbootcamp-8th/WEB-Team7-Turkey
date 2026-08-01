package com.turkey.quick.location.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.location.dto.RiderLocationUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "rider-location", description = "라이더 현재 위치 갱신")
@RequestMapping("/api/rider/location")
public interface RiderLocationUpdateApi {

    @Operation(operationId = "updateRiderLocation", summary = "라이더 현재 위치 갱신")
    @PostMapping
    ApiResponse<Void> updateRiderLocation(@Valid @RequestBody RiderLocationUpdateRequest request);

}
