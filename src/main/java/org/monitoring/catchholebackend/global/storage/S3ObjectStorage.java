package org.monitoring.catchholebackend.global.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.config.S3StorageProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@Profile("!e2e")
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
            var putObjectResponse = s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(resolveContentType(contentType))
                            .build(),
                    RequestBody.fromBytes(bytes)
            );
            return new StoredObject(key, putObjectResponse.versionId());
        } catch (S3Exception exception) {
            throw new AppException(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR, "S3 파일 저장에 실패했습니다.", exception);
        }
    }

    @Override
    public String getText(String key) {
        return new String(getBytes(key), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] getBytes(String key) {
        try (ResponseInputStream<GetObjectResponse> getObjectResponseStream = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .build()
        )) {
            return getObjectResponseStream.readAllBytes();
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

    @Override
    public ObjectStoragePurgeResult purgePrefixes(Collection<String> prefixes) {
        return purgePrefixesExcluding(prefixes, List.of());
    }

    @Override
    public ObjectStoragePurgeResult purgePrefixesExcluding(
            Collection<String> prefixes,
            Collection<String> retainedKeys
    ) {
        int targetCount = 0;
        int deletedCount = 0;
        int failedCount = 0;
        Set<String> retainedKeySet = new HashSet<>(retainedKeys);
        for (String prefix : prefixes) {
            List<ObjectIdentifier> targets;
            try {
                targets = listAllVersions(prefix).stream()
                        .filter(target -> !retainedKeySet.contains(target.key()))
                        .toList();
            } catch (AppException exception) {
                failedCount++;
                continue;
            }
            targetCount += targets.size();
            for (int start = 0; start < targets.size(); start += 1000) {
                List<ObjectIdentifier> batch = targets.subList(start, Math.min(start + 1000, targets.size()));
                try {
                    var response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(properties.getBucket())
                            .delete(delete -> delete.objects(batch).quiet(false))
                            .build());
                    deletedCount += response.deleted().size();
                    failedCount += response.errors().size();
                } catch (S3Exception exception) {
                    failedCount += batch.size();
                }
            }
        }
        return new ObjectStoragePurgeResult(targetCount, deletedCount, failedCount);
    }

    private List<ObjectIdentifier> listAllVersions(String prefix) {
        List<ObjectIdentifier> targets = new ArrayList<>();
        try {
            s3Client.listObjectVersionsPaginator(ListObjectVersionsRequest.builder()
                            .bucket(properties.getBucket())
                            .prefix(prefix)
                            .build())
                    .forEach(response -> {
                        response.versions().forEach(version -> targets.add(ObjectIdentifier.builder()
                                .key(version.key())
                                .versionId(version.versionId())
                                .build()));
                        response.deleteMarkers().forEach(marker -> targets.add(ObjectIdentifier.builder()
                                .key(marker.key())
                                .versionId(marker.versionId())
                                .build()));
                    });
            return targets;
        } catch (S3Exception exception) {
            throw new AppException(
                    CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR,
                    "S3 영구 삭제 대상을 조회하지 못했습니다.",
                    exception
            );
        }
    }

    private String resolveContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType : "application/octet-stream";
    }
}
