package com.turkey.quick.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 업로드 클라이언트(#61 후속, 배송 완료 인증 사진). 자격 증명은 기본 프로바이더 체인
 * (환경변수 → 시스템 프로퍼티 → {@code ~/.aws/credentials} → EC2 인스턴스 프로파일)을 그대로 쓴다.
 *
 * <p>업로드는 라이더 클라이언트가 {@code S3Presigner.presignPutObject}로 발급한 URL로 S3에 직접
 * 하고, 서버는 완료 처리 시 HeadObject로 재검증만 한다(PUT presign + 사후검증 방식, 서버가
 * 파일 전체를 메모리로 받아 동기 업로드하던 이전 방식에서 전환). 커넥션 풀·타임아웃은 여전히
 * 튜닝하지 않은 가장 단순한 구성이다.
 */
@Configuration
@ConditionalOnProperty(name = "rider.delivery-proof.storage", havingValue = "s3")
public class S3Config {

    @Value("${aws.s3.region}")
    private String region;

    @Bean
    public S3Client amazonS3() {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }


}
