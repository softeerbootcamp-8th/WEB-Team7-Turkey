package com.turkey.quick.rider.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 로컬 프로파일에서 프로젝트의 data 디렉터리에 인증 파일을 저장한다. */
@Component
@Profile("local")
public class LocalRiderDeliveryProofStorage implements RiderDeliveryProofStorage {

    private final Path rootDirectory;

    public LocalRiderDeliveryProofStorage(
            @Value("${rider.delivery-proof.local-root:data}") String rootDirectory) {
        this.rootDirectory = Path.of(rootDirectory).toAbsolutePath().normalize();
    }

    @Override
    public void store(String key, MultipartFile file) {
        Path target = resolveSafely(key);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("인증 파일을 로컬 저장소에 저장하지 못했습니다.", e);
        }
    }

    @Override
    public RiderDeliveryProofFile read(String key) {
        Path source = resolveSafely(key);
        try {
            return new RiderDeliveryProofFile(
                    source.getFileName().toString(), Files.probeContentType(source),
                    Files.size(source), Files.newInputStream(source));
        } catch (IOException e) {
            throw new IllegalStateException("인증 파일을 로컬 저장소에서 읽지 못했습니다.", e);
        }
    }

    private Path resolveSafely(String key) {
        Path resolved = rootDirectory.resolve(key).normalize();
        if (!resolved.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("저장소 경로를 벗어난 파일 키입니다.");
        }
        return resolved;
    }
}
