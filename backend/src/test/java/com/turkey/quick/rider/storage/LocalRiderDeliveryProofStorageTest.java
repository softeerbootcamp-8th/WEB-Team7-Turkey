package com.turkey.quick.rider.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalRiderDeliveryProofStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("프로젝트 데이터 경로에 저장하고 읽는다")
    void shouldStoreAndReadFromProjectDataPath() throws Exception {
        LocalRiderDeliveryProofStorage storage =
                new LocalRiderDeliveryProofStorage(tempDirectory.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});
        String key = "proof/2026/08/42/photo_123e4567-e89b-42d3-a456-426614174000.jpg";

        storage.store(key, file);
        RiderDeliveryProofFile result = storage.read(key);

        assertThat(tempDirectory.resolve(key)).exists();
        assertThat(result.contentLength()).isEqualTo(3);
        assertThat(result.inputStream().readAllBytes()).containsExactly(1, 2, 3);
        result.inputStream().close();
    }

    @Test
    @DisplayName("상위 경로로 벗어나는 키는 거부한다")
    void shouldRejectKeyEscapingParentPath() {
        LocalRiderDeliveryProofStorage storage =
                new LocalRiderDeliveryProofStorage(tempDirectory.toString());

        assertThatThrownBy(() -> storage.read("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
