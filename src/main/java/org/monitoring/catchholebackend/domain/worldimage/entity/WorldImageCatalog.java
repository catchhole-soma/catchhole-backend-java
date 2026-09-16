package org.monitoring.catchholebackend.domain.worldimage.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Getter
@Entity
@Table(name = "world_image_catalog")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldImageCatalog {
    @Id
    @Column(length = 100)
    private String id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WorldSettingCategory category;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "search_text", nullable = false, columnDefinition = "text")
    private String searchText;
    @Column(name = "is_default", nullable = false)
    private boolean defaultImage;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "thumbnail_sha", nullable = false, length = 64)
    private String thumbnailSha;
    @Column(name = "image_sha", nullable = false, length = 64)
    private String imageSha;
    @ElementCollection
    @CollectionTable(name = "world_image_recommendations", joinColumns = @JoinColumn(name = "catalog_id"))
    @Column(name = "theme", nullable = false, length = 30)
    private Set<String> themes = new LinkedHashSet<>();

    // 별칭은 대표 이미지 검색용 관련 표현이며, 원고의 설정명이나 대상명을 치환하지 않는다.
    @ElementCollection
    @CollectionTable(name = "world_image_aliases", joinColumns = @JoinColumn(name = "catalog_id"))
    @Column(name = "alias", nullable = false, length = 150)
    @BatchSize(size = 100)
    private Set<String> aliases = new LinkedHashSet<>();
}
