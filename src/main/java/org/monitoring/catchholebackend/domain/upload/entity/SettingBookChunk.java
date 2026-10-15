package org.monitoring.catchholebackend.domain.upload.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter @Entity
@Table(name = "setting_book_chunks", uniqueConstraints = @UniqueConstraint(name="uk_setting_book_chunks_job_index",columnNames={"analysis_job_id","chunk_index"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettingBookChunk extends BaseEntity {
    @Id @Column(nullable=false,updatable=false) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @org.hibernate.annotations.OnDelete(action=org.hibernate.annotations.OnDeleteAction.CASCADE)
    @JoinColumn(name="analysis_job_id",nullable=false) private AnalysisJob analysisJob;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="setting_book_revision_id",nullable=false) private SettingBookRevision settingBookRevision;
    @Column(name="chunk_index",nullable=false) private int chunkIndex;
    @Column(name="chunk_text",nullable=false,columnDefinition="text") private String chunkText;
    @Column(name="start_offset",nullable=false) private int startOffset;
    @Column(name="end_offset",nullable=false) private int endOffset;
    @Column(name="paragraph_start_index",nullable=false) private int paragraphStartIndex;
    @Column(name="paragraph_end_index",nullable=false) private int paragraphEndIndex;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="metadata_json",columnDefinition="jsonb") private JsonNode metadataJson;
}
