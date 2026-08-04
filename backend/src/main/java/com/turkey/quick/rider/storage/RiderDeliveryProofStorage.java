package com.turkey.quick.rider.storage;

import org.springframework.web.multipart.MultipartFile;

/** 배송 완료 인증 파일 저장소. 활성 프로파일에 따라 로컬 파일시스템 또는 S3를 사용한다. */
public interface RiderDeliveryProofStorage {

    void store(String key, MultipartFile file);

    RiderDeliveryProofFile read(String key);

    /** key 를 실제 저장 위치로 해석한다(로컬은 절대경로, S3는 객체 URL). 파일을 읽지는 않는다. */
    String resolvePath(String key);
}
