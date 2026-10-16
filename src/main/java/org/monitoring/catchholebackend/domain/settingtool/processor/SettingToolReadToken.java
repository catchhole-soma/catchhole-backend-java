package org.monitoring.catchholebackend.domain.settingtool.processor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolQuery;

/** 저장된 값의 의미를 판단하지 않고 동일한 모집단을 페이지 사이에 읽었는지 확인한다. */
public final class SettingToolReadToken {
    private SettingToolReadToken() {}

    public static String create(UUID workId, SettingToolQuery query, List<Object[]> subjects, List<Object[]> schemas) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "setting-current-collection-v1");
            add(digest, workId);
            add(digest, query.tool());
            add(digest, query.factType());
            add(digest, query.settingKey());
            add(digest, query.scopeName());
            add(digest, query.worldCategory());
            addRows(digest, subjects);
            addRows(digest, schemas);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("변경 감지 해시를 생성할 수 없습니다.", impossible);
        }
    }

    private static void addRows(MessageDigest digest, List<Object[]> rows) {
        add(digest, rows.size());
        for (var row : rows) {
            add(digest, row.length);
            for (var value : row) add(digest, value);
        }
    }

    private static void add(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(4).putInt(-1).array());
            return;
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
