package org.monitoring.catchholebackend.global.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.config.S3StorageProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@RequiredArgsConstructor
public class S3ObjectStorage implements ObjectStorage {

    private static final String TEXT_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private final S3Client s3Client;
    private final S3StorageProperties properties;

    @Override
    public StoredObject putText(String key, String content) {
        return putBytes(key, content.getBytes(StandardCharsets.UTF_8), TEXT_CONTENT_TYPE);
    }

    @Override
    public StoredObject putBytes(String key, byte[] bytes, String contentType) {
        try {
            var response = s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(resolveContentType(contentType))
                            .build(),
                    RequestBody.fromBytes(bytes)
            );
            return new StoredObject(key, response.versionId());
        } catch (S3Exception exception) {
            throw new AppException(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR, "S3 파일 저장에 실패했습니다.", exception);
        }
    }

    @Override
    public String getText(String key) {
        try (ResponseInputStream<GetObjectResponse> object = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .build()
        )) {
            return new String(object.readAllBytes(), StandardCharsets.UTF_8);
        } catch (S3Exception | IOException exception) {
            throw new AppException(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR, "S3 파일 조회에 실패했습니다.", exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
        } catch (S3Exception exception) {
            throw new AppException(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR, "S3 파일 삭제에 실패했습니다.", exception);
        }
    }

    private String resolveContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType : "application/octet-stream";
    }
}
