package com.turkey.quick.rider.service;

import com.turkey.quick.rider.storage.RiderDeliveryProofFile;
import com.turkey.quick.rider.storage.RiderDeliveryProofStorage;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 배송 완료 인증 사진의 저장 키를 만들고 프로파일에 맞는 저장소에 위임한다.
 *
 * 사진 업로드는 라이더 클라이언트가 presigned URL로 S3에 직접 하고(#61 후속, PUT +
 * HeadObject 사후검증 방식으로 전환), 이 서비스는 키 발급·presign 위임·검증용 조회만 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderDeliveryProofUploadService {

    /** presigned 업로드 URL 유효 시간(잠정값). */
    public static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);

    private static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");

    private static final Pattern STORED_FILENAME_PATTERN = Pattern.compile(
            "^(.*)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                    + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12})(\\.[^.]*)?$");

    private final RiderDeliveryProofStorage storage;

    public boolean isAllowedContentType(String contentType) {
        return ALLOWED_CONTENT_TYPES.containsKey(contentType);
    }

    /** 서버가 파일을 직접 받아 저장하는 경로(현재 호출자 없음 — presigned PUT 전환 후 보존용). */
    public String upload(Long deliveryId, MultipartFile file) {
        String key = buildKey(deliveryId, file.getOriginalFilename());
        storage.store(key, file);

        log.info("event=RIDER_DELIVERY_PROOF_PHOTO_UPLOADED orderId={} key={} sizeBytes={}",
                deliveryId, key, file.getSize());
        return key;
    }

    /**
     * 이미 정해진 키로 업로드만 수행한다 — 완료 트랜잭션이 먼저 커밋된 뒤 지연 업로드하는
     * 경로 전용이다(사람 확인). {@link #upload}와 달리 키 생성을 하지 않는다: 호출자가
     * 트랜잭션 커밋 *전*에 {@link #buildKey}로 키를 미리 만들어 DB에 심어 두고, 커밋 *후*
     * 이 메서드로 실제 파일을 그 키에 올린다 — 실패해도 DB에는 이미 그 키를 가리키는
     * 인증 기록이 남아 있어 완전한 유령 데이터가 되지 않는다.
     */
    public void store(String key, MultipartFile file) {
        storage.store(key, file);
        log.info("event=RIDER_DELIVERY_PROOF_PHOTO_UPLOADED key={} sizeBytes={}", key, file.getSize());
    }

    /** upload()가 쓰는 키 생성 — 클라이언트가 보낸 원본 파일명을 그대로 반영한다. */
    String buildKey(Long deliveryId, String originalFilename) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String safeFilename = safeFilename(originalFilename);
        int dot = safeFilename.lastIndexOf('.');
        String basename = dot > 0 ? safeFilename.substring(0, dot) : safeFilename;
        String extension = dot > 0 ? safeFilename.substring(dot) : "";

        return "proof/%d/%02d/%d/%s_%s%s".formatted(
                today.getYear(), today.getMonthValue(), deliveryId,
                basename, UUID.randomUUID(), extension);
    }

    /** 이 배송의 인증 사진 업로드 전용 키를 발급한다. deliveryId를 경로에 담아 소유권 검증 근거로 쓴다. */
    public String buildUploadKey(Long deliveryId, String contentType) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String extension = ALLOWED_CONTENT_TYPES.getOrDefault(contentType, "");
        return "proof/%d/%02d/%d/%s%s".formatted(
                today.getYear(), today.getMonthValue(), deliveryId, UUID.randomUUID(), extension);
    }

    /** key가 buildUploadKey로 이 배송에 발급된 키인지 확인한다(다른 배송 키 재사용 방지). */
    public boolean belongsToDelivery(String key, Long deliveryId) {
        return key != null && key.contains("/" + deliveryId + "/");
    }

    public String presignUploadUrl(String key, String contentType) {
        return storage.presignUpload(key, contentType, UPLOAD_URL_TTL);
    }

    /** HeadObject로 실제 업로드 여부·크기를 확인한다. 없으면 비어 있다. */
    public Optional<Long> sizeOf(String key) {
        return storage.sizeOf(key);
    }

    /** 사후검증 실패(크기 초과 등) 시 업로드된 객체를 정리한다. */
    public void delete(String key) {
        storage.delete(key);
    }

    /** 저장 파일명의 UUID 접미사를 제거해 사용자가 올린 파일명으로 돌려준다. */
    public RiderDeliveryProofFile read(String key) {
        RiderDeliveryProofFile storedFile = storage.read(key);
        return storedFile.withFilename(originalFilenameOf(key));
    }

    String originalFilenameOf(String key) {
        String filename = safeFilename(key);
        Matcher matcher = STORED_FILENAME_PATTERN.matcher(filename);
        if (!matcher.matches()) {
            return filename;
        }
        return matcher.group(1) + (matcher.group(3) == null ? "" : matcher.group(3));
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "proof";
        }
        String normalized = filename.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (basename.isBlank() || basename.equals(".") || basename.equals("..")) {
            return "proof";
        }
        return basename.replaceAll("[\\r\\n]", "_");
    }
}
