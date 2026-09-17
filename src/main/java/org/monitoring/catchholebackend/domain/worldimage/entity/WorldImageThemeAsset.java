package org.monitoring.catchholebackend.domain.worldimage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "world_image_theme_assets", uniqueConstraints = @UniqueConstraint(columnNames = {"theme", "purpose", "slot"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldImageThemeAsset extends BaseEntity {
    @Id
    @Column(length = 100)
    private String id;
    @Column(nullable = false, length = 30)
    private String theme;
    // OVERVIEW는 전체(ALL)를 포함한 초기 8칸, DEFAULT는 분류별 기본 7칸이다.
    @Column(nullable = false, length = 10)
    private String purpose;
    @Column(nullable = false, length = 40)
    private String slot;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "thumbnail_sha", nullable = false, length = 64)
    private String thumbnailSha;
    @Column(name = "image_sha", nullable = false, length = 64)
    private String imageSha;
}

