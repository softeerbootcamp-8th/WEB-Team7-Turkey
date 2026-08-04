package com.turkey.quick.rider.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 배송 완료 인증 사진을 S3 에 올린다(#61 후속, {@link RiderDeliveryService#complete} 에서만 부른다).
 *
 * <p><b>가장 단순한 구현이다(사람 확인, 의도적).</b> 요청 스레드가 파일 전체를 메모리로 읽어
 * {@code AmazonS3.putObject()} 로 동기 업로드한다 — 스트리밍도, 사전서명(Presigned URL)도,
 * 비동기 처리도 없다. 부하 상황에서 이 방식의 병목(메모리·스레드 점유)을 먼저 실측한 뒤
 * Presigned URL 방식으로 옮길 계획이다. 근거: {@code docs/worklog/2026-08-04-61-delivery-completion-proof.md}.
 *
 * <p>배정 라이더·주문 상태 검증은 하지 않는다 — 호출자({@code complete()})가 이미
 * {@code validateCompletionTarget} 으로 검증을 끝낸 뒤에만 이 서비스를 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderDeliveryProofUploadService {

    private final AmazonS3 amazonS3;

    @Value("${aws.s3.bucket}")
    private String bucket;

    public String upload(Long deliveryId, MultipartFile file) {
        String key = buildKey(deliveryId, file.getOriginalFilename());

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(file.getContentType());

        try (InputStream inputStream = file.getInputStream()) {
            amazonS3.putObject(new PutObjectRequest(bucket, key, inputStream, metadata));
        } catch (IOException e) {
            throw new IllegalStateException("업로드 파일을 읽는 중 오류가 발생했습니다.", e);
        }

        log.info("event=RIDER_DELIVERY_PROOF_PHOTO_UPLOADED orderId={} key={} sizeBytes={}",
                deliveryId, key, file.getSize());

        return key;
    }

    private String buildKey(Long deliveryId, String originalFilename) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return "proof/%d/%02d/%d-%s%s".formatted(
                today.getYear(), today.getMonthValue(), deliveryId, UUID.randomUUID(),
                extensionOf(originalFilename));
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
