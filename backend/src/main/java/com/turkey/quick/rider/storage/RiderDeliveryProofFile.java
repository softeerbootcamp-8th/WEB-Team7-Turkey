package com.turkey.quick.rider.storage;

import java.io.InputStream;

/** 읽은 인증 파일. inputStream은 사용한 쪽에서 닫아야 한다. */
public record RiderDeliveryProofFile(
        String filename,
        String contentType,
        long contentLength,
        InputStream inputStream
) {
    public RiderDeliveryProofFile withFilename(String newFilename) {
        return new RiderDeliveryProofFile(newFilename, contentType, contentLength, inputStream);
    }
}
