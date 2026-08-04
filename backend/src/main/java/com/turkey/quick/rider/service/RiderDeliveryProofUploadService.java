package com.turkey.quick.rider.service;

import com.turkey.quick.rider.storage.RiderDeliveryProofFile;
import com.turkey.quick.rider.storage.RiderDeliveryProofStorage;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 배송 완료 인증 파일의 저장 키를 만들고 프로파일에 맞는 저장소에 위임한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderDeliveryProofUploadService {

    private static final Pattern STORED_FILENAME_PATTERN = Pattern.compile(
            "^(.*)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                    + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12})(\\.[^.]*)?$");

    private final RiderDeliveryProofStorage storage;

    public String upload(Long deliveryId, MultipartFile file) {
        String key = buildKey(deliveryId, file.getOriginalFilename());
        storage.store(key, file);

        log.info("event=RIDER_DELIVERY_PROOF_PHOTO_UPLOADED orderId={} key={} sizeBytes={}",
                deliveryId, key, file.getSize());
        return key;
    }

    /** 저장 파일명의 UUID 접미사를 제거해 사용자가 올린 파일명으로 돌려준다. */
    public RiderDeliveryProofFile read(String key) {
        RiderDeliveryProofFile storedFile = storage.read(key);
        return storedFile.withFilename(originalFilenameOf(key));
    }

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
