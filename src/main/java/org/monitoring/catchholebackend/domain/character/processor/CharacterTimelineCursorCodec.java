package org.monitoring.catchholebackend.domain.character.processor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/**
 * FE가 cursor 내용을 해석하지 않도록 URL-safe 문자열로 직렬화한다.
 * 현재 데이터 규모에서는 안정된 정렬에 offset을 적용하고, cursor를 캐릭터·필터·시작 회차에 결합한다.
 */
@Component
public class CharacterTimelineCursorCodec {

    private static final String VERSION = "2";
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final int MAX_OFFSET = 1_000_000;
    private static final int FILTER_FINGERPRINT_LENGTH = 43;

    public String encode(CharacterTimelineCursor cursor) {
        String fromEpisodeNo = cursor.fromEpisodeNo() == null
                ? ""
                : cursor.fromEpisodeNo().toString();
        String payload = String.join(
                ":",
                VERSION,
                cursor.characterId().toString(),
                cursor.filterFingerprint(),
                fromEpisodeNo,
                Integer.toString(cursor.offset())
        );
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public CharacterTimelineCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }

        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );
            String[] fields = payload.split(":", -1);
            if (fields.length != 5 || !VERSION.equals(fields[0])) {
                throw invalidCursor();
            }

            UUID characterId = UUID.fromString(fields[1]);
            String filterFingerprint = fields[2];
            Integer fromEpisodeNo = fields[3].isBlank() ? null : Integer.valueOf(fields[3]);
            int offset = Integer.parseInt(fields[4]);
            if (filterFingerprint.length() != FILTER_FINGERPRINT_LENGTH
                    || (fromEpisodeNo != null && fromEpisodeNo < 1)
                    || offset < 0
                    || offset > MAX_OFFSET) {
                throw invalidCursor();
            }
            return new CharacterTimelineCursor(characterId, filterFingerprint, fromEpisodeNo, offset);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private AppException invalidCursor() {
        return new AppException(CharacterErrorCode.CHARACTER_TIMELINE_CURSOR_INVALID);
    }
}
