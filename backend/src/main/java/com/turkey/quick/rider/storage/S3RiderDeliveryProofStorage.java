package com.turkey.quick.rider.storage;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** local 이외의 프로파일에서 배송 인증 파일을 실제 S3에 저장한다. */
@Component
@Profile("!local")
public class S3RiderDeliveryProofStorage implements RiderDeliveryProofStorage {

    private final AmazonS3 amazonS3;
    private final String bucket;

    public S3RiderDeliveryProofStorage(
            AmazonS3 amazonS3,
            @Value("${aws.s3.bucket}") String bucket) {
        this.amazonS3 = amazonS3;
        this.bucket = bucket;
    }

    @Override
    public void store(String key, MultipartFile file) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(file.getContentType());

        try (InputStream inputStream = file.getInputStream()) {
            amazonS3.putObject(new PutObjectRequest(bucket, key, inputStream, metadata));
        } catch (IOException e) {
            throw new IllegalStateException("업로드 파일을 읽는 중 오류가 발생했습니다.", e);
        }
    }

    @Override
    public RiderDeliveryProofFile read(String key) {
        S3Object object = amazonS3.getObject(bucket, key);
        ObjectMetadata metadata = object.getObjectMetadata();
        return new RiderDeliveryProofFile(
                key.substring(key.lastIndexOf('/') + 1), metadata.getContentType(),
                metadata.getContentLength(), object.getObjectContent());
    }

    @Override
    public String resolvePath(String key) {
        // 1. URL 만료 시간 설정 (현재 시간으로부터)
        Date expiration = new Date();
        long expTimeMillis = expiration.getTime();
        expTimeMillis += 1000 * 10; // 10초 (밀리초 단위)
        expiration.setTime(expTimeMillis);

        // 2. 프리사인 URL 생성 요청서 작성
        GeneratePresignedUrlRequest generatePresignedUrlRequest =
                new GeneratePresignedUrlRequest(bucket, key)
                        .withMethod(HttpMethod.GET) // 조회용
                        .withExpiration(expiration);

        // 3. AWS 서버 통신 없이 내부 대칭키 알고리즘으로 URL 생성
        URL url = amazonS3.generatePresignedUrl(generatePresignedUrlRequest);

        return url.toString();
    }
}
