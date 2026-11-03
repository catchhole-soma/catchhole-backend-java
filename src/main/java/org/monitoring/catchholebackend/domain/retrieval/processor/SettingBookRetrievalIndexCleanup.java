package org.monitoring.catchholebackend.domain.retrieval.processor;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 설정집 수정·보관은 그 설정집의 RAG 색인(모든 revision의 조각·임베딩)을 같은 트랜잭션에서 지운다.
 * 색인 범위는 접수 때 현재 판인 revision뿐이고(2026-11-02 결정), 다음 검수 접수가 새 현재 판의 빈 곳을 채운다.
 */
@Component
@RequiredArgsConstructor
public class SettingBookRetrievalIndexCleanup {
    private final JdbcTemplate jdbc;

    @EventListener
    public void purge(ValidationStagingEvents.SettingBookSourceChanged event) {
        deleteSettingBookIndex(event.settingBookId());
    }

    public int deleteSettingBookIndex(UUID settingBookId) {
        int deleted = jdbc.update("""
                delete from retrieval_setting_book_chunks
                where revision_id in (select revision_id from retrieval_setting_book_sources where setting_book_id = ?)
                """, settingBookId);
        return deleted + jdbc.update("delete from retrieval_setting_book_sources where setting_book_id = ?", settingBookId);
    }
}
