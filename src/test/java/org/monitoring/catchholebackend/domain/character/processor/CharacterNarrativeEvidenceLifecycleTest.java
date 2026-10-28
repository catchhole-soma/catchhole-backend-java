package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.SettingBookAutomaticEvidenceValidator;
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
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("원문 교체·동일 원문 재분석 이후 서사 근거의 수명")
class CharacterNarrativeEvidenceLifecycleTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String content = "🌱봄 첫날에 손이 회복되었다.";
    private JdbcTemplate jdbc;
    private ObjectStorageService storage;
    private CharacterCurrentSupportingEvidenceResolver resolver;
    private Work work;
    private Episode episode;
    private AnalysisJob job;
    private SettingCandidate candidate;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:narrative-lifecycle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"));
        jdbc.execute("create table episode_chunks(id uuid primary key, episode_id uuid, start_offset integer, end_offset integer, chunk_text varchar)");
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
        storage = mock(ObjectStorageService.class);
        when(storage.getText("immutable/lifecycle.txt")).thenReturn(content);
        when(storage.contentHash(content)).thenReturn("a".repeat(64));
        resolver = new CharacterCurrentSupportingEvidenceResolver(storage, new SettingBookAutomaticEvidenceValidator(storage), jdbc);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("shutdown");
    }

    @Test
    @DisplayName("같은 원문 재청킹으로 옛 UUID가 없어져도 완료한 관측 근거는 계속 검증한다")
    void completedObservationSurvivesRechunkedIdenticalSource() {
        insertChunk(candidate.getSourceChunkId(), episode.getId(), 0, length(), content);
        JsonNode before = capture();
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        jdbc.update("delete from episode_chunks where episode_id = ?", episode.getId());
        insertChunk(UUID.randomUUID(), episode.getId(), 0, length(), content);

        assertThat(capture()).isEqualTo(before);
        assertThat(candidate.getNarrativeTime().path("referenceTime").path("evidenceSpans").get(0)
                .path("sourceChunkId").asText()).isEqualTo(candidate.getSourceChunkId().toString());
    }

    @Test
    @DisplayName("확정된 관측은 원 Job의 최종 상태와 무관하게 불변 원문으로 재검증한다")
    void reviewedObservationCanRecoverMissingChunk() {
        candidate.confirm();
        assertThat(capture().path("availability").asText()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("새 추출의 존재하지 않는 청크 UUID는 기존 이력 복구로 통과시키지 않는다")
    void newObservationStillRequiresItsChunk() {
        assertThatThrownBy(this::capture).isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test
    @DisplayName("다른 회차에 존재하는 청크 UUID는 원문 인용이 같아도 거절한다")
    void foreignEpisodeChunkCannotUseHistoricalRecovery() {
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        insertChunk(candidate.getSourceChunkId(), UUID.randomUUID(), 0, length(), content);
        assertThatThrownBy(this::capture).isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test
    @DisplayName("현재 존재하는 청크의 잘못된 범위와 본문은 복구하지 않는다")
    void existingMalformedChunkIsRejected() {
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        insertChunk(candidate.getSourceChunkId(), episode.getId(), 1, length(), content);
        assertThatThrownBy(this::capture).isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test
    @DisplayName("옛 청크가 없어도 고정 원문 hash와 정확한 Unicode 위치 검증은 유지한다")
    void missingChunkCannotHideChangedSourceOrForgedOffsets() {
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        when(storage.contentHash(content)).thenReturn("b".repeat(64));
        assertThatThrownBy(this::capture).isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((org.monitoring.catchholebackend.global.exception.AppException)error).getResultCode().getStatus().value()).isEqualTo(409));
        when(storage.contentHash(content)).thenReturn("a".repeat(64));
        ((ObjectNode) candidate.getNarrativeTime().path("referenceTime").path("evidenceSpans").get(0)).put("startOffset", 1);
        assertThatThrownBy(this::capture).isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
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
        insertChunk(candidate.getSourceChunkId(), episode.getId(), 0, length(), content);
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
    private void insertChunk(UUID chunk, UUID sourceEpisode, int start, int end, String text) {
        jdbc.update("insert into episode_chunks values (?, ?, ?, ?, ?)", chunk, sourceEpisode, start, end, text);
    }
}
