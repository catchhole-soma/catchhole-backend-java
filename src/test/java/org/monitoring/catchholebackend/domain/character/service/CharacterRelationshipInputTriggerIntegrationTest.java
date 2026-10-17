package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterSettingUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSourceManager;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("캐릭터 현재 설정과 식별 변경의 관계 재처리 입력 세대")
class CharacterRelationshipInputTriggerIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private CharacterService characterService;
    @Autowired private SettingCandidatePromotionService promotionService;
    @Autowired private CharacterSnapshotSourceManager sourceManager;
    @Autowired private PlatformTransactionManager transactionManager;
    private Member member;
    private Work work;
    private WorkCharacter character;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        member = Member.register(unique + "@relationship.test", "encoded-password", "01012345678", "작가");
        entityManager.persist(member);
        work = Work.create(member, "관계 입력 변경", WorkGenre.FANTASY, null);
        entityManager.persist(work);
        character = WorkCharacter.create(work, "아리아", null, null, null, null, null, null, null, null, null);
        entityManager.persist(character);
        entityManager.flush();
    }

    @Test
    @DisplayName("프로필 직접 수정과 삭제는 세대를 올리고 같은 값 재저장은 유지한다")
    void manualProfileUpdateAndRemovalMarkInputButNoOpDoesNot() {
        CharacterSettingUpdateRequest profile = new CharacterSettingUpdateRequest(
                "profile.manual_family", "B의 자녀", SettingValueType.STRING, List.of());
        CharacterUpdateRequest update = update("아리아", null, List.of(profile));

        characterService.updateCharacter(member.getId(), work.getId(), character.getId(), update);
        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
        characterService.updateCharacter(member.getId(), work.getId(), character.getId(), update);
        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
        characterService.updateCharacter(member.getId(), work.getId(), character.getId(), update("아리아", null, List.of()));
        assertThat(work.getRelationshipInputVersion()).isEqualTo(2);

        UUID workId = work.getId();
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(Work.class, workId).getRelationshipInputVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("이름 변경은 재처리를 표시하고 역할 변경과 동일 이름 저장은 표시하지 않는다")
    void onlyNameChangeMarksIdentityUpdate() {
        characterService.updateCharacter(member.getId(), work.getId(), character.getId(), update("아리아", "주인공", List.of()));
        assertThat(work.getRelationshipInputVersion()).isZero();
        characterService.updateCharacter(member.getId(), work.getId(), character.getId(), update("세룸", "주인공", List.of()));
        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
        characterService.updateCharacter(member.getId(), work.getId(), character.getId(), update(" 세룸 ", "주인공", List.of()));
        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("캐릭터 보관과 복구는 각각 작품의 관계 재처리를 표시한다")
    void archiveAndRestoreMarkRelationshipInput() {
        characterService.archiveCharacter(member.getId(), work.getId(), character.getId());
        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
        characterService.restoreCharacter(member.getId(), work.getId(), character.getId());
        assertThat(work.getRelationshipInputVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNRESOLVED", "NEW_GROUP", "ORDERED"})
    @DisplayName("세 신규 인물 확정 경로는 Fact가 없는 발견 후보도 관계 재처리를 표시한다")
    void newlyDiscoveredCharacterMarksInputAcrossAllPromotionPaths(String path) {
        AnalysisJob job = null;
        Episode episode = null;
        if (path.equals("ORDERED")) {
            episode = Episode.create(work, null, 1, "첫 화", "synthetic/source.txt", "v1", "a".repeat(64), 10);
            entityManager.persist(episode);
            job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
            job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, new AnalysisStateJournal().emptyState());
            entityManager.persist(job);
        }
        SettingCandidate discovery = SettingCandidate.createCharacterDiscovery(work, episode, null, job,
                "새로운 가족 인물", "새로운 가족 인물", null, SettingCandidateMatchStatus.UNRESOLVED,
                JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null);
        entityManager.persist(discovery);
        if (path.equals("ORDERED")) {
            ReflectionTestUtils.setField(discovery, "provisionalSubjectKey", "provisional-character:" + discovery.getId());
        }
        entityManager.flush();

        if (path.equals("NEW_GROUP")) {
            promotionService.promoteNewCharacterGroup(List.of(new SettingCandidateGroupPromotion(
                    discovery, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL)));
        } else {
            discovery.confirm();
            promotionService.promote(discovery, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        }

        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
        assertThat(discovery.getMatchedCharacterId()).isNotNull();
        assertThat(entityManager.createQuery("select fact from CharacterFact fact", CharacterFact.class).getResultList()).isEmpty();
    }

    @Test
    @DisplayName("기존 캐릭터의 HISTORY_ONLY 프로필 Fact는 현재 관계 재처리를 만들지 않는다")
    void historicalOnlyProfileFactDoesNotMarkCurrentInput() {
        entityManager.persist(CharacterSettingSchema.create(work, "profile.family", null, "가족 관계",
                CharacterFactType.PROFILE, SettingValueType.STRING, CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE, JsonNodeFactory.instance.arrayNode(), CharacterSettingSchemaSource.DEV_SEED, true));
        SettingCandidate candidate = SettingCandidate.create(work, null, null, null, SettingEntityType.CHARACTER,
                character.getName(), character.getName(), character.getId(), SettingCandidateMatchStatus.MATCHED,
                "profile.family", "B의 자녀", SettingValueType.STRING,
                JsonNodeFactory.instance.objectNode().put("value", "B의 자녀"),
                JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null);
        candidate.startComparison();
        candidate.recordComparisonContext(character.getSnapshotVersion(), "history-only-context");
        candidate.completeComparison(CharacterFactOperation.HISTORY_ONLY, null, null, null, null,
                JsonNodeFactory.instance.arrayNode(), CharacterFactTemporalScope.PAST, "과거 기록만 저장",
                JsonNodeFactory.instance.objectNode(), LocalDateTime.now());
        candidate.confirm();
        entityManager.persist(candidate);
        entityManager.flush();

        promotionService.promote(candidate, CharacterFactConfirmApplicationMode.HISTORY_ONLY);

        assertThat(work.getRelationshipInputVersion()).isZero();
        assertThat(entityManager.createQuery("select fact from CharacterFact fact", CharacterFact.class).getResultList()).hasSize(1);
        assertThat(character.getProfileJson()).isNull();
    }

    @Test
    @DisplayName("설정 트랜잭션이 롤백되면 관계 재처리 세대와 새 Fact도 함께 롤백한다")
    void rollbackKeepsInputVersionAndSourceMutationAtomic() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        record Fixture(Long memberId, UUID workId, UUID characterId) {}
        Fixture fixture = transaction.execute(status -> {
            Member owner = Member.register(UUID.randomUUID() + "@rollback.test", "encoded-password", "01087654321", "작가");
            entityManager.persist(owner);
            Work targetWork = Work.create(owner, "롤백 검증", WorkGenre.FANTASY, null);
            entityManager.persist(targetWork);
            WorkCharacter target = WorkCharacter.create(targetWork, "가족 인물", null, null, null,
                    null, null, null, null, null, null);
            entityManager.persist(target);
            return new Fixture(owner.getId(), targetWork.getId(), target.getId());
        });
        try {
            transaction.executeWithoutResult(status -> {
                Work targetWork = entityManager.find(Work.class, fixture.workId(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                WorkCharacter target = entityManager.find(WorkCharacter.class, fixture.characterId());
                CharacterFact fact = CharacterFact.createManual(target, CharacterFactType.PROFILE, "profile.family", "A의 자녀", null);
                entityManager.persist(fact);
                sourceManager.replaceSources(target, new CharacterSnapshotSlot(CharacterFactType.PROFILE, "profile.family"), List.of(fact));
                assertThat(targetWork.getRelationshipInputVersion()).isEqualTo(1);
                entityManager.flush();
                status.setRollbackOnly();
            });
            transaction.executeWithoutResult(status -> {
                assertThat(entityManager.find(Work.class, fixture.workId()).getRelationshipInputVersion()).isZero();
                assertThat(entityManager.createQuery("select count(fact) from CharacterFact fact where fact.workCharacter.id = :id", Long.class)
                        .setParameter("id", fixture.characterId()).getSingleResult()).isZero();
            });
        } finally {
            transaction.executeWithoutResult(status -> {
                entityManager.remove(entityManager.find(WorkCharacter.class, fixture.characterId()));
                entityManager.remove(entityManager.find(Work.class, fixture.workId()));
                entityManager.remove(entityManager.find(Member.class, fixture.memberId()));
            });
        }
    }

    private CharacterUpdateRequest update(String name, String role, List<CharacterSettingUpdateRequest> profile) {
        return new CharacterUpdateRequest(name, role, null, null, null, profile, List.of(), List.of(), List.of(), List.of());
    }
}
