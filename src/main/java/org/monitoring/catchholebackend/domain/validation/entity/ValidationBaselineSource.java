package org.monitoring.catchholebackend.domain.validation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** 검색 가능한 원문 버전의 허용 목록. 원문/검색 인덱스 준비 상태와 독립된 의존 관계다. */
@Entity
@Table(name = "validation_baseline_sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationBaselineSource extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "report_id", nullable = false, updatable = false) private ValidationReport report;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "episode_id", updatable = false) private Episode episode;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "setting_book_revision_id", updatable = false) private SettingBookRevision settingBookRevision;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "source_manifest", nullable = false, columnDefinition = "jsonb") private JsonNode sourceManifest;
    public static ValidationBaselineSource create(ValidationReport report, Episode episode, SettingBookRevision revision, JsonNode manifest) {
        if ((episode == null) == (revision == null)) throw new IllegalArgumentException("원문 출처는 하나만 지정해야 합니다.");
        ValidationBaselineSource source = new ValidationBaselineSource(); source.report = report; source.episode = episode;
        source.settingBookRevision = revision; source.sourceManifest = manifest.deepCopy(); return source;
    }
}
