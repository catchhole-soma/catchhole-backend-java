package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("원문 교체·동일 원문 재분석 이후 서사 근거의 수명")
class CharacterNarrativeEvidenceLifecycleTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String content = "🌱봄 첫날에 손이 회복되었다.";
    private CharacterCurrentSupportingEvidenceResolver resolver;
    private Work work;
    private Episode episode;
    private AnalysisJob job;
    private SettingCandidate candidate;

    @BeforeEach
    void setUp() {
        work = Work.create(Member.register("lifecycle@example.invalid", "secret", "01000000000", "합성 작가"), "합성 작품", WorkGenre.FANTASY, "");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        episode = Episode.create(work, null, 3, "합성 3화", "immutable/lifecycle.txt", null, "a".repeat(64), length());
        ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "sourceEpisodeNo", 3);
        ReflectionTestUtils.setField(job, "sourceContentHash", "a".repeat(64));
        ReflectionTestUtils.setField(job, "sourceContentS3Key", "immutable/lifecycle.txt");
        var evidence = json.createArrayNode().add(json.createObjectNode().put("quote", content)
                .put("startOffset", 0).put("endOffset", length()));
        candidate = SettingCandidate.create(work, episode, UUID.randomUUID(), job, SettingEntityType.CHARACTER,
                "합성 인물", "profile.손", "회복", SettingValueType.STRING, null, evidence, null, json.createObjectNode());
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.ADD);
        ReflectionTestUtils.setField(candidate, "temporalScope", CharacterFactTemporalScope.PRESENT);
        var point = json.createObjectNode().put("description", "봄 첫날");
        point.set("evidenceSpans", json.createArrayNode().add(json.createObjectNode().put("quote", content)
                .put("startOffset", 0).put("endOffset", length()).put("sourceChunkId", candidate.getSourceChunkId().toString())));
        candidate.recordNarrativeTime(json.createObjectNode().putNull("factTime").set("referenceTime", point));
        resolver = new CharacterCurrentSupportingEvidenceResolver(org.monitoring.catchholebackend.support.StoredCharacterEvidenceTestSupport.verified(content), org.monitoring.catchholebackend.support.AnalysisJobSourceViewTestSupport.repositoryFor(job));
    }

    @Test
    @DisplayName("확정된 관측은 원 Job의 완료 상태와 무관하게 저장 검증을 재사용한다")
    void reviewedObservationReusesStoredVerification() {
        candidate.confirm();
        assertThat(capture().path("availability").asText()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("잘못된 UUID와 다른 작품 출처는 이력 근거 복구에서도 거절한다")
    void malformedIdentityAndForeignWorkRemainInvalid() {
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        ObjectNode span = (ObjectNode) candidate.getNarrativeTime().path("referenceTime").path("evidenceSpans").get(0);
        span.put("sourceChunkId", "not-a-uuid");
        assertThatThrownBy(this::capture).isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
        span.put("sourceChunkId", candidate.getSourceChunkId().toString());
        var other = Work.create(work.getMember(), "다른 작품", WorkGenre.FANTASY, "");
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "work", other);
        assertThatThrownBy(this::capture).isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test
    @DisplayName("원문을 두 번 교체해도 새로 검증한 관측으로 대표 시점을 이어가고 확정값은 보존한다")
    void repeatedPurgeAllowsFreshGroundedSelectionWithoutChangingConfirmedValues() {
        var character = WorkCharacter.create(work, "합성 인물", null, null, null, null, null, null, null, null, null);
        var profile = json.createObjectNode();
        profile.putObject("profile.손").put("value", "작가 확정값");
        character.replaceCurrentSnapshots(null, null, profile, null, null, null, null);
        ObjectNode original = ((ObjectNode) candidate.getNarrativeTime().path("referenceTime")).deepCopy();
        original.put("sourceCandidateId", UUID.randomUUID().toString());
        character.updateCurrentNarrativeTime(CharacterNarrativeTime.purgeEvidence(original), true);
        for (int replacement = 0; replacement < 2; replacement++) {
            JsonNode current = character.getCurrentNarrativeTime();
            JsonNode verified = resolver.captureNarrativeTime(resolver.begin(work), candidate, candidate.getNarrativeTime());
            candidate.recordNarrativeTimeSelection(CharacterNarrativeTime.selection(candidate, true, current, verified));
            JsonNode next = CharacterNarrativeTime.selected(candidate, current);
            assertThat(next).isNotNull();
            assertThat(character.updateCurrentNarrativeTime(next, true)).isTrue();
            assertThat(character.getCurrentNarrativeTime().path("evidenceSpans")).hasSize(1);
            assertThat(new CharacterSnapshotAccessor().read(character, java.util.Map.of()).values())
                    .anySatisfy(entry -> assertThat(entry.factValue()).isEqualTo("작가 확정값"));
            if (replacement == 0) character.updateCurrentNarrativeTime(CharacterNarrativeTime.purgeEvidence(next), true);
        }
    }

    private JsonNode capture() { return resolver.captureCandidate(resolver.begin(work), candidate); }
    private int length() { return content.codePointCount(0, content.length()); }
}
