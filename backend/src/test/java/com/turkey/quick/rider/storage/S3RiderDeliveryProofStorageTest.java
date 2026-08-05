package com.turkey.quick.rider.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class S3RiderDeliveryProofStorageTest {

    private final AmazonS3 amazonS3 = mock(AmazonS3.class);
    private final S3RiderDeliveryProofStorage storage =
            new S3RiderDeliveryProofStorage(amazonS3, "proof-bucket");

    @Test
    void 파일을_지정한_키로_S3에_업로드한다() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(amazonS3.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());

        storage.store("proof/42/photo_uuid.jpg", file);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(amazonS3).putObject(captor.capture());
        assertThat(captor.getValue().getBucketName()).isEqualTo("proof-bucket");
        assertThat(captor.getValue().getKey()).isEqualTo("proof/42/photo_uuid.jpg");
        assertThat(captor.getValue().getMetadata().getContentType()).isEqualTo("image/jpeg");
        assertThat(captor.getValue().getMetadata().getContentLength()).isEqualTo(3);
    }

    @Test
    void S3에서_파일과_메타데이터를_읽는다() throws Exception {
        String key = "proof/42/photo_uuid.jpg";
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("image/jpeg");
        metadata.setContentLength(3);
        S3Object object = new S3Object();
        object.setObjectMetadata(metadata);
        object.setObjectContent(new S3ObjectInputStream(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), null));
        when(amazonS3.getObject("proof-bucket", key)).thenReturn(object);

        RiderDeliveryProofFile result = storage.read(key);

        assertThat(result.filename()).isEqualTo("photo_uuid.jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.contentLength()).isEqualTo(3);
        assertThat(result.inputStream().readAllBytes()).containsExactly(1, 2, 3);
        result.inputStream().close();
    }
}
