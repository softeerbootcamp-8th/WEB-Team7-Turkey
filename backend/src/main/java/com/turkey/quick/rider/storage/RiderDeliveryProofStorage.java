package com.turkey.quick.rider.storage;

import org.springframework.web.multipart.MultipartFile;

/** 배송 완료 인증 파일 저장소. 활성 프로파일에 따라 로컬 파일시스템 또는 S3를 사용한다. */
public interface RiderDeliveryProofStorage {

    void store(String key, MultipartFile file);

    RiderDeliveryProofFile read(String key);
}
