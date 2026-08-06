package com.turkey.quick.common.config;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * S3 업로드 클라이언트(#61 후속, 배송 완료 인증 사진). 자격 증명은 기본 프로바이더 체인
 * (환경변수 → 시스템 프로퍼티 → {@code ~/.aws/credentials} → EC2 인스턴스 프로파일)을 그대로 쓴다.
 *
 * <p>가장 단순한 구성이다 — 커넥션 풀·타임아웃을 의도적으로 튜닝하지 않았고, 업로드도
 * {@code RiderDeliveryProofUploadService} 에서 요청 스레드가 파일 전체를 메모리로 받아
 * 동기 호출로 올린다. 부하 상황에서 병목을 먼저 실측한 뒤 Presigned URL 방식으로 옮길 계획이다
 * (사람 확인, {@code docs/worklog/2026-08-04-61-delivery-completion-proof.md}).
 */
@Configuration
@Profile("!local")
public class S3Config {

    @Value("${aws.s3.region}")
    private String region;

    @Bean
    public AmazonS3 amazonS3() {
        return AmazonS3ClientBuilder.standard()
                .withRegion(region)
                // ec2 자체에 롤을 줌
//                .withCredentials(new DefaultAWSCredentialsProviderChain())
                .build();
    }
}
