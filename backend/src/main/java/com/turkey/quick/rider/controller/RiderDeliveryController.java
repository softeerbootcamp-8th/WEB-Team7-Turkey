package com.turkey.quick.rider.controller;

import com.turkey.quick.common.response.ApiResponse;
import com.turkey.quick.order.domain.ProofType;
import com.turkey.quick.rider.auth.AuthenticatedRider;
import com.turkey.quick.rider.auth.RiderSessionInterceptor;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteMultipartRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteRequest;
import com.turkey.quick.rider.dto.RiderDeliveryCompleteResponse;
import com.turkey.quick.rider.dto.RiderDeliveryProofUploadUrlRequest;
import com.turkey.quick.rider.dto.RiderDeliveryProofUploadUrlResponse;
import com.turkey.quick.rider.dto.RiderDeliveryResponse;
import com.turkey.quick.rider.dto.RiderDeliveryTransitionRequest;
import com.turkey.quick.rider.service.RiderDeliveryService;
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
            @RequestBody RiderDeliveryTransitionRequest request) {
        return ApiResponse.ok(riderDeliveryService.transition(rider, deliveryId, request.action()));
    }

    @Override
    @PostMapping("/{deliveryId}/proof-photo-upload-url")
    public ApiResponse<RiderDeliveryProofUploadUrlResponse> issueProofPhotoUploadUrl(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @PathVariable Long deliveryId,
            @RequestBody RiderDeliveryProofUploadUrlRequest request) {
        return ApiResponse.ok(riderDeliveryService.issueProofPhotoUploadUrl(rider, deliveryId, request));
    }

    /**
     * 기존 프론트가 이미 이 URL·Content-Type으로 붙어 있다(원래 #61 후속 구현). 경로를 그대로
     * 유지해 프론트 연동을 안 건드린다 — presign 경로는 신규 URL({@link #completeDeliveryWithProofKey})
     * 로 뺐다(사람 확인, 2026-08-11).
     */
    @Override
    @PostMapping(value = "/{deliveryId}/complete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RiderDeliveryCompleteResponse> completeDelivery(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @PathVariable Long deliveryId,
            @RequestParam("proofType") ProofType proofType,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "proofValue", required = false) String proofValue) {
        RiderDeliveryCompleteMultipartRequest request =
                new RiderDeliveryCompleteMultipartRequest(proofType, file, proofValue);
        return ApiResponse.ok(riderDeliveryService.completeWithPhotoFile(rider, deliveryId, request));
    }

    /**
     * presigned 업로드 URL({@link #issueProofPhotoUploadUrl})로 미리 받은 저장소 키로 완료하는
     * 신규 경로. 기존 멀티파트 URL({@link #completeDelivery})과 별도로 뺐다 — OpenAPI의 paths는
     * (경로, HTTP 메서드)로 오퍼레이션을 하나만 담을 수 있어, 같은 경로에 consumes만 다르게 둔
     * 이전 시도는 springdoc이 하나만 스펙에 남기고 나머지를 버렸다(사람 확인, 2026-08-11).
     */
    @Override
    @PostMapping(value = "/{deliveryId}/complete/proof-key", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RiderDeliveryCompleteResponse> completeDeliveryWithProofKey(
            @RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider rider,
            @PathVariable Long deliveryId,
            @RequestBody RiderDeliveryCompleteRequest request) {
        return ApiResponse.ok(riderDeliveryService.complete(rider, deliveryId, request));
    }
}
