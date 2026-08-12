package com.turkey.quick.rider.storage;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** 저장소 설정이 s3일 때 배송 인증 파일을 실제 S3에 저장한다. */
@Component
@ConditionalOnProperty(name = "rider.delivery-proof.storage", havingValue = "s3")
public class S3RiderDeliveryProofStorage implements RiderDeliveryProofStorage {

    private final S3Client amazonS3;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public S3RiderDeliveryProofStorage(
            S3Client amazonS3,
            S3Presigner s3Presigner,
            @Value("${aws.s3.bucket}") String bucket) {
        this.amazonS3 = amazonS3;
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
    }

    /** 서버가 파일을 직접 받아 저장하는 경로(현재 호출자 없음 — presigned PUT 전환 후 보존용). */
    @Override
    public void store(String key, MultipartFile file) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(file.getContentType())
                        .contentLength(file.getSize())
                        .build();

        try (InputStream inputStream = file.getInputStream()) {
            amazonS3.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, file.getSize()));
        } catch (IOException e) {
            throw new IllegalStateException("업로드 파일을 읽는 중 오류가 발생했습니다.", e);
        }
    }

    @Override
    public RiderDeliveryProofFile read(String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        ResponseInputStream<GetObjectResponse> responseStream = amazonS3.getObject(getObjectRequest);

        GetObjectResponse response = responseStream.response();

        return new RiderDeliveryProofFile(
                key.substring(key.lastIndexOf('/') + 1),
                response.contentType(),     // Content-Type 추출
                response.contentLength(),   // Content-Length 추출
                responseStream              // S3 전송 인풋 스트림 자체를 전달
        );
    }

    @Override
    public String resolvePath(String key) {
        // 1. 객체 조회용 GetObjectRequest 생성
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        // 2. 만료 시간(10초) 및 Get 요청을 래핑하는 Presign 생성서 작성
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(10)) // 10초 만료 설정
                .getObjectRequest(getObjectRequest)
                .build();

        // 3. AWS 서버 통신 없이 내부 알고리즘으로 URL 생성
        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);

        return presignedRequest.url().toString();
    }

    @Override
    public String presignUpload(String key, String contentType, Duration ttl) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
        return presignedRequest.url().toString();
    }

    @Override
    public Optional<Long> sizeOf(String key) {
        try {
            HeadObjectResponse response = amazonS3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return Optional.of(response.contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        amazonS3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }
}
