package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteResponse;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import com.turkey.quick.rider.dto.RiderDeliveryTransitionRequest;
import com.turkey.quick.rider.service.RiderDeliveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rider/deliveries")
public class RiderDeliveryController implements RiderDeliveryApi {

    private final RiderDeliveryService riderDeliveryService;

    @Override
    @GetMapping("/current")
    public ApiResponse<RiderDeliveryResponse> getCurrentDelivery(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider) {
        return ApiResponse.ok(riderDeliveryService.getCurrentDelivery(rider));
    }

    @Override
    @PostMapping("/{deliveryId}/transition")
    public ApiResponse<RiderDeliveryResponse> transitionDelivery(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @PathVariable Long deliveryId,
            @Valid @RequestBody RiderDeliveryTransitionRequest request) {
        return ApiResponse.ok(riderDeliveryService.transition(rider, deliveryId, request.action()));
    }

    /**
     * multipart/form-data 다 — proofType=PHOTO 면 file 을 함께 받아 서버가 그 자리에서 S3 에
     * 올린다(#61 후속). record 파라미터를 {@code @ModelAttribute} 로 자동 바인딩하지 않고
     * 필드별로 받아 DTO 를 직접 만든다 — multipart 바인딩은 실패 시 예외 타입이 갈리기 쉬워
     * (BindException vs MethodArgumentNotValidException), 검증을 서비스 계층
     * ({@link RiderDeliveryService#complete}) 하나로 모으는 편이 더 단순하다.
     */
    @Override
    @PostMapping(value = "/{deliveryId}/complete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RiderDeliveryCompleteResponse> completeDelivery(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @PathVariable Long deliveryId,
            @RequestParam("proofType") ProofType proofType,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "proofValue", required = false) String proofValue) {
        RiderDeliveryCompleteRequest request =
                new RiderDeliveryCompleteRequest(proofType, file, proofValue);
        return ApiResponse.ok(riderDeliveryService.complete(rider, deliveryId, request));
    }
}
