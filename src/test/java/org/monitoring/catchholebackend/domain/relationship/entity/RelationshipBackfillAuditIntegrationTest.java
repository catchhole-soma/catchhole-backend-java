package org.monitoring.catchholebackend.domain.relationship.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillItemState;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillWorkState;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.auditing.CurrentDateTimeProvider;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Transactional
@DisplayName("관계 백필 엔티티의 생성·수정 시각 감사")
class RelationshipBackfillAuditIntegrationTest {
    @Autowired EntityManager em;
    @Autowired AuditingHandler auditingHandler;
    @MockitoBean ObjectStorageService storage;

    @AfterEach
    void restoreAuditingClock() {
        auditingHandler.setDateTimeProvider(CurrentDateTimeProvider.INSTANCE);
    }

    @Test
    @DisplayName("캠페인·작품·회차의 생성 시각은 보존하고 상태 변경 시 수정 시각을 갱신한다")
    void auditsCreationAndStateChangesForEveryBackfillEntity() {
        var createdAt = LocalDateTime.of(2026, 10, 30, 10, 0);
        auditingHandler.setDateTimeProvider(() -> Optional.of(createdAt));
        var member = Member.register("backfill-audit@test.invalid", "password", null, "감사 검증 작가");
        em.persist(member);
        var work = Work.create(member, "감사 검증 작품", WorkGenre.FANTASY, null);
        em.persist(work);
        var episode =
                Episode.create(
                        work, null, 1, "1화", "synthetic/backfill-audit", "v1", "a".repeat(64), 10);
        em.persist(episode);
        var campaign = RelationshipBackfillCampaign.create("backfill-audit-test");
        em.persist(campaign);
        var workState = RelationshipBackfillWork.create(work.getId(), campaign.getId());
        em.persist(workState);
        var item = RelationshipBackfillItem.create(episode, 1, true);
        em.persist(item);
        em.flush();
        em.clear();

        var savedCampaign = em.find(RelationshipBackfillCampaign.class, campaign.getId());
        var savedWork = em.find(RelationshipBackfillWork.class, work.getId());
        var savedItem = em.find(RelationshipBackfillItem.class, item.getId());
        assertAuditTimestamps(List.of(savedCampaign, savedWork, savedItem), createdAt, createdAt);
        assertThat(savedWork.getState()).isEqualTo(RelationshipBackfillWorkState.BUILDING);
        assertThat(savedItem.getState()).isEqualTo(RelationshipBackfillItemState.WAITING);

        var updatedAt = createdAt.plusMinutes(1);
        auditingHandler.setDateTimeProvider(() -> Optional.of(updatedAt));
        savedCampaign.initialize();
        savedWork.block("SOURCE_CHANGED");
        savedItem.fail("SOURCE_CHANGED");
        em.flush();
        em.clear();

        savedCampaign = em.find(RelationshipBackfillCampaign.class, campaign.getId());
        savedWork = em.find(RelationshipBackfillWork.class, work.getId());
        savedItem = em.find(RelationshipBackfillItem.class, item.getId());
        assertAuditTimestamps(List.of(savedCampaign, savedWork, savedItem), createdAt, updatedAt);
        assertThat(savedCampaign.isInitialized()).isTrue();
        assertThat(savedWork.getState()).isEqualTo(RelationshipBackfillWorkState.BLOCKED);
        assertThat(savedItem.getState()).isEqualTo(RelationshipBackfillItemState.FAILED);
    }

    private void assertAuditTimestamps(
            List<BaseEntity> entities, LocalDateTime createdAt, LocalDateTime updatedAt) {
        assertThat(entities)
                .allSatisfy(
                        entity -> {
                            assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
                            assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);
                        });
    }
}
