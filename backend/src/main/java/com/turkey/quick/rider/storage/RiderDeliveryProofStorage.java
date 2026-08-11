package com.turkey.quick.rider.storage;

import java.time.Duration;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

/** 배송 완료 인증 파일 저장소. 활성 프로파일에 따라 로컬 파일시스템 또는 S3를 사용한다. */
public interface RiderDeliveryProofStorage {

    /** 서버가 파일을 직접 받아 저장하는 경로(현재 호출자 없음 — presigned PUT 전환 후 보존용). */
    void store(String key, MultipartFile file);

    RiderDeliveryProofFile read(String key);

    /** key 를 실제 저장 위치로 해석한다(로컬은 절대경로, S3는 객체 URL). 파일을 읽지는 않는다. */
    String resolvePath(String key);

    /** 라이더가 이 key 로 직접 PUT 업로드할 수 있는 presigned URL을 발급한다. */
    default String presignUpload(String key, String contentType, Duration ttl) {
        throw new UnsupportedOperationException("이 저장소는 presigned 업로드를 지원하지 않습니다.");
    }

    /** 객체 크기를 조회한다(HeadObject). 없으면 비어 있다. */
    default Optional<Long> sizeOf(String key) {
        throw new UnsupportedOperationException("이 저장소는 객체 크기 조회를 지원하지 않습니다.");
    }

    /** 사후검증 실패 시(크기 초과 등) 업로드된 객체를 정리한다. */
    default void delete(String key) {
        throw new UnsupportedOperationException("이 저장소는 삭제를 지원하지 않습니다.");
    }
}
