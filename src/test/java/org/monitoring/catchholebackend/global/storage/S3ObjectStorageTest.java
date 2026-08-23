package org.monitoring.catchholebackend.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.monitoring.catchholebackend.global.config.S3StorageProperties;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageTest {

    @Mock private S3Client s3Client;
    @Mock private ListObjectVersionsIterable paginator;
    @Mock private ListObjectVersionsIterable failedPaginator;

    private S3ObjectStorage objectStorage;

    @BeforeEach
    void setUp() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setBucket("manuscripts");
        objectStorage = new S3ObjectStorage(s3Client, properties);
    }

    @Test
    void purgePrefixDeletesEveryObjectVersionAndDeleteMarker() {
        ListObjectVersionsResponse page = ListObjectVersionsResponse.builder()
                .versions(
                        ObjectVersion.builder().key("works/work-1/a.txt").versionId("v2").build(),
                        ObjectVersion.builder().key("works/work-1/a.txt").versionId("v1").build()
                )
                .deleteMarkers(DeleteMarkerEntry.builder()
                        .key("works/work-1/a.txt")
                        .versionId("marker-1")
                        .build())
                .build();
        when(s3Client.listObjectVersionsPaginator(any(ListObjectVersionsRequest.class))).thenReturn(paginator);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ListObjectVersionsResponse> consumer = invocation.getArgument(0);
            consumer.accept(page);
            return null;
        }).when(paginator).forEach(any());
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                .deleted(
                        DeletedObject.builder().key("works/work-1/a.txt").versionId("v2").build(),
                        DeletedObject.builder().key("works/work-1/a.txt").versionId("v1").build(),
                        DeletedObject.builder().key("works/work-1/a.txt").versionId("marker-1").build()
                )
                .build());

        ObjectStoragePurgeResult result = objectStorage.purgePrefixes(List.of("works/work-1/"));

        assertThat(result).isEqualTo(new ObjectStoragePurgeResult(3, 3, 0));
        ArgumentCaptor<DeleteObjectsRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client).deleteObjects(requestCaptor.capture());
        assertThat(requestCaptor.getValue().delete().objects())
                .extracting(identifier -> identifier.key() + ":" + identifier.versionId())
                .containsExactlyInAnyOrder(
                        "works/work-1/a.txt:v2",
                        "works/work-1/a.txt:v1",
                        "works/work-1/a.txt:marker-1"
                );
    }

    @Test
    void purgePrefixKeepsEveryVersionOfExplicitlyRetainedKey() {
        ListObjectVersionsResponse page = ListObjectVersionsResponse.builder()
                .versions(
                        ObjectVersion.builder().key("works/work-1/old.txt").versionId("old-v1").build(),
                        ObjectVersion.builder().key("works/work-1/new.txt").versionId("new-v1").build()
                )
                .build();
        when(s3Client.listObjectVersionsPaginator(any(ListObjectVersionsRequest.class))).thenReturn(paginator);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ListObjectVersionsResponse> consumer = invocation.getArgument(0);
            consumer.accept(page);
            return null;
        }).when(paginator).forEach(any());
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                .deleted(DeletedObject.builder().key("works/work-1/old.txt").versionId("old-v1").build())
                .build());

        ObjectStoragePurgeResult result = objectStorage.purgePrefixesExcluding(
                List.of("works/work-1/"),
                List.of("works/work-1/new.txt")
        );

        assertThat(result).isEqualTo(new ObjectStoragePurgeResult(1, 1, 0));
        ArgumentCaptor<DeleteObjectsRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client).deleteObjects(requestCaptor.capture());
        assertThat(requestCaptor.getValue().delete().objects())
                .extracting(identifier -> identifier.key() + ":" + identifier.versionId())
                .containsExactly("works/work-1/old.txt:old-v1");
    }

    @Test
    void purgePrefixesKeepsCompletedCountsWhenLaterPrefixListingFails() {
        ListObjectVersionsResponse page = ListObjectVersionsResponse.builder()
                .versions(ObjectVersion.builder()
                        .key("works/work-1/source.txt")
                        .versionId("v1")
                        .build())
                .build();
        when(s3Client.listObjectVersionsPaginator(any(ListObjectVersionsRequest.class)))
                .thenAnswer(invocation -> {
                    ListObjectVersionsRequest request = invocation.getArgument(0);
                    return request.prefix().equals("works/work-1/") ? paginator : failedPaginator;
                });
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ListObjectVersionsResponse> consumer = invocation.getArgument(0);
            consumer.accept(page);
            return null;
        }).when(paginator).forEach(any());
        doAnswer(invocation -> {
            throw S3Exception.builder().message("목록 조회 실패").build();
        }).when(failedPaginator).forEach(any());
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder()
                        .deleted(DeletedObject.builder()
                                .key("works/work-1/source.txt")
                                .versionId("v1")
                                .build())
                        .build());

        ObjectStoragePurgeResult result = objectStorage.purgePrefixes(List.of(
                "works/work-1/",
                "upload-batches/batch-1/"
        ));

        assertThat(result).isEqualTo(new ObjectStoragePurgeResult(1, 1, 1));
    }
}
