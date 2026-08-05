package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.turkey.quick.rider.storage.RiderDeliveryProofFile;
import com.turkey.quick.rider.storage.RiderDeliveryProofStorage;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class RiderDeliveryProofUploadServiceTest {

    private final RiderDeliveryProofStorage storage = mock(RiderDeliveryProofStorage.class);
    private final RiderDeliveryProofUploadService service = new RiderDeliveryProofUploadService(storage);

    @Test
    void 업로드할_때_원본파일명_뒤에_UUID를_붙인다() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "door.photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        String key = service.upload(42L, file);

        assertThat(key).matches(
                "proof/\\d{4}/\\d{2}/42/door\\.photo_[0-9a-f-]{36}\\.jpg");
        verify(storage).store(key, file);
    }

    @Test
    void 파일을_읽을_때_파일명_뒤의_UUID를_제거한다() throws Exception {
        String key = "proof/2026/08/42/delivery_photo_"
                + "123e4567-e89b-42d3-a456-426614174000.jpg";
        ByteArrayInputStream content = new ByteArrayInputStream(new byte[]{1});
        when(storage.read(key)).thenReturn(
                new RiderDeliveryProofFile("stored-name", "image/jpeg", 1, content));

        RiderDeliveryProofFile result = service.read(key);

        assertThat(result.filename()).isEqualTo("delivery_photo.jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.inputStream()).isSameAs(content);
        result.inputStream().close();
    }

    @Test
    void UUID_형식이_아닌_접미사는_파일명에서_제거하지_않는다() {
        assertThat(service.originalFilenameOf("proof/42/photo_final.jpg"))
                .isEqualTo("photo_final.jpg");
    }
}
