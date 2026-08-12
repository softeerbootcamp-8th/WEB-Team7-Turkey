package com.turkey.quick.rider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.turkey.quick.rider.storage.RiderDeliveryProofFile;
import com.turkey.quick.rider.storage.RiderDeliveryProofStorage;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class RiderDeliveryProofUploadServiceTest {

    private final RiderDeliveryProofStorage storage = mock(RiderDeliveryProofStorage.class);
    private final RiderDeliveryProofUploadService service = new RiderDeliveryProofUploadService(storage);

    @Test
    @DisplayName("업로드할 때 원본파일명 뒤에 UUID를 붙인다")
    void shouldAppendUuidToOriginalFilenameOnUpload() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "door.photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        String key = service.upload(42L, file);

        assertThat(key).matches(
                "proof/\\d{4}/\\d{2}/42/door\\.photo_[0-9a-f-]{36}\\.jpg");
        verify(storage).store(key, file);
    }

    @Test
    @DisplayName("파일을 읽을 때 파일명 뒤의 UUID를 제거한다")
    void shouldRemoveUuidSuffixWhenReadingFilename() throws Exception {
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
    @DisplayName("UUID 형식이 아닌 접미사는 파일명에서 제거하지 않는다")
    void shouldPreserveNonUuidSuffixInFilename() {
        assertThat(service.originalFilenameOf("proof/42/photo_final.jpg"))
                .isEqualTo("photo_final.jpg");
    }
}
