package com.turkey.quick.rider.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

class S3RiderDeliveryProofStorageTest {

    private final S3Client amazonS3 = mock(S3Client.class);
    private final S3Presigner s3Presigner = mock(S3Presigner.class);
    private final S3RiderDeliveryProofStorage storage =
            new S3RiderDeliveryProofStorage(amazonS3, s3Presigner, "proof-bucket");

    @Test
    @DisplayName("파일을 지정한 키로 S3에 업로드한다")
    void shouldUploadFileToSpecifiedS3Key() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        storage.store("proof/42/photo_uuid.jpg", file);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(amazonS3).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo("proof-bucket");
        assertThat(captor.getValue().key()).isEqualTo("proof/42/photo_uuid.jpg");
        assertThat(captor.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(captor.getValue().contentLength()).isEqualTo(3);
    }

    @Test
    @DisplayName("S3에서 파일과 메타데이터를 읽는다")
    void shouldReadFileAndMetadataFromS3() throws Exception {
        String key = "proof/42/photo_uuid.jpg";
        GetObjectResponse response = GetObjectResponse.builder()
                .contentType("image/jpeg")
                .contentLength(3L)
                .build();
        ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                response,
                AbortableInputStream.create(new ByteArrayInputStream(new byte[]{1, 2, 3})));
        when(amazonS3.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        RiderDeliveryProofFile result = storage.read(key);

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(amazonS3).getObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("proof-bucket");
        assertThat(captor.getValue().key()).isEqualTo(key);
        assertThat(result.filename()).isEqualTo("photo_uuid.jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.contentLength()).isEqualTo(3);
        assertThat(result.inputStream().readAllBytes()).containsExactly(1, 2, 3);
        result.inputStream().close();
    }

    @Test
    @DisplayName("S3 객체의 10초 만료 Presigned URL을 생성한다")
    void shouldCreatePresignedUrlExpiringInTenSeconds() throws Exception {
        PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
        when(presignedRequest.url()).thenReturn(URI.create("https://example.com/proof.jpg").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenReturn(presignedRequest);

        String result = storage.resolvePath("proof/42/photo_uuid.jpg");

        ArgumentCaptor<GetObjectPresignRequest> captor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(captor.capture());
        assertThat(captor.getValue().signatureDuration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(captor.getValue().getObjectRequest().bucket()).isEqualTo("proof-bucket");
        assertThat(captor.getValue().getObjectRequest().key())
                .isEqualTo("proof/42/photo_uuid.jpg");
        assertThat(result).isEqualTo("https://example.com/proof.jpg");
    }
}
