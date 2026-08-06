package com.turkey.quick.rider.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalRiderDeliveryProofStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void 프로젝트_데이터_경로에_저장하고_읽는다() throws Exception {
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
    void 상위_경로로_벗어나는_키는_거부한다() {
        LocalRiderDeliveryProofStorage storage =
                new LocalRiderDeliveryProofStorage(tempDirectory.toString());

        assertThatThrownBy(() -> storage.read("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
