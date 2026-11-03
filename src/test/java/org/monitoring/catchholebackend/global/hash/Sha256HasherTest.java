package org.monitoring.catchholebackend.global.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.monitoring.catchholebackend.global.storage.StoredObject;

@DisplayName("공유 SHA-256의 원본 바이트와 기존 저장소 해시 호환")
class Sha256HasherTest {
    private final Sha256Hasher hasher = new Sha256Hasher();

    @ParameterizedTest
    @MethodSource("vectors")
    @DisplayName("UTF-8·공백·CRLF를 그대로 해시하며 저장소 없는 계산도 같은 값을 반환한다")
    void preservesUtf8BytesAndStorageHashCompatibility(String text, String expected) {
        ObjectStorage storage = mock(ObjectStorage.class);
        assertThat(hasher.hash(text)).isEqualTo(expected);
        assertThat(new ObjectStorageService(storage, hasher).contentHash(text)).isEqualTo(expected);
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("회차와 설정집 저장본도 원본 내용을 바꾸지 않고 같은 해시를 기록한다")
    void recordsCompatibleHashesForEpisodeAndSettingBookWrites() {
        ObjectStorage storage = mock(ObjectStorage.class);
        String content = "가나다 🌙\r\n ";
        when(storage.putText(anyString(), eq(content))).thenReturn(new StoredObject("key", "v1"));
        ObjectStorageService service = new ObjectStorageService(storage, hasher);
        var episode = service.putEpisodeContent(UUID.randomUUID(), 1, content);
        var book = service.putSettingBookRevision(UUID.randomUUID(), UUID.randomUUID(), "설정집.txt", content);
        assertThat(episode.contentHash()).isEqualTo("e8f394e883dac706293fffa634bce2dbadf83663ea0d19a63b2285d8878d1b59");
        assertThat(book.contentHash()).isEqualTo(episode.contentHash());
        assertThat(episode.charCount()).isEqualTo(4);
        assertThat(book.charCount()).isEqualTo(episode.charCount());
    }

    private static Stream<Arguments> vectors() {
        return Stream.of(
                Arguments.of("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                Arguments.of("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                Arguments.of("가나다 🌙\r\n ", "e8f394e883dac706293fffa634bce2dbadf83663ea0d19a63b2285d8878d1b59"),
                Arguments.of("가나다 🌙\n ", "b73a984d3e52d390e5c558d4de0c23be0eca3c77b58a3e6c1196fc25cd8bf1f5"));
    }
}
