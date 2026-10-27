package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("CURRENT 근거 capture는 원 manifest와 실제 확정 canonical Fact를 hash 이전에 동결한다")
class CharacterCurrentSupportingEvidenceCaptureTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WorkCharacterRepository characters = mock(WorkCharacterRepository.class);
    private final CharacterSnapshotSourceRepository sources = mock(CharacterSnapshotSourceRepository.class);
    private final SettingCandidateRepository candidates = mock(SettingCandidateRepository.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository factRepository =
            mock(org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private CharacterAnalysisStateSource capture;
    private Work work;
    private WorkCharacter character;
    private AnalysisJob origin;
    private Episode episode;
    private SettingCandidate candidate;
    private CharacterFact fact;
    private final String content = "😀오른손 감각: 정상 — 회복 확인";
    private final String key = "PROFILE:profile.오른손_감각";

    @BeforeEach void setup() {
        work = Work.create(Member.register("freeze@example.invalid","secret","01000000000","작가"),
                "고정 근거",WorkGenre.FANTASY,"");
        ReflectionTestUtils.setField(work,"id",UUID.randomUUID());
        character = WorkCharacter.create(work,"한서린",null,null,null,null,null,null,null,null,null);
        ReflectionTestUtils.setField(character,"id",UUID.randomUUID());
        ObjectNode profile = json.createObjectNode();
        profile.putObject("profile.오른손_감각").put("value","정상");
        character.replaceCurrentSnapshots(null,null,profile,null,null,null,null);
        episode = Episode.create(work,null,3,"회복","immutable/ep3.txt",null,"a".repeat(64),24);
        ReflectionTestUtils.setField(episode,"id",UUID.randomUUID());
        origin = AnalysisJob.create(work,null,episode,AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(origin,"id",UUID.randomUUID());
        ReflectionTestUtils.setField(origin,"sourceEpisodeNo",3);
        ReflectionTestUtils.setField(origin,"sourceContentHash","a".repeat(64));
        ReflectionTestUtils.setField(origin,"sourceContentS3Key","immutable/ep3.txt");
        candidate = SettingCandidate.create(work,episode,UUID.randomUUID(),origin,SettingEntityType.CHARACTER,
                "한서린","profile.손감각","정상",SettingValueType.STRING,json.createObjectNode().put("value","정상"),
                json.createArrayNode().add(json.createObjectNode().put("quote",content)
                        .put("start_offset",0).put("end_offset",content.codePointCount(0,content.length()))),
                BigDecimal.ONE,json.createObjectNode());
        ReflectionTestUtils.setField(candidate,"id",UUID.randomUUID());
        ReflectionTestUtils.setField(candidate,"matchedCharacterId",character.getId());
        fact = CharacterFact.create(character,candidate,CharacterFactType.PROFILE,"profile.오른손_감각",
                "정상","정상",json.createObjectNode().put("value","정상"),episode,null,origin,BigDecimal.ONE,3);
        when(characters.findAllByWorkIdAndStatusOrderByCreatedAtDesc(work.getId(),CharacterStatus.ACTIVE))
                .thenReturn(List.of(character));
        when(candidates.findConfirmedDiscoveries(work.getId())).thenReturn(List.of());
        when(storage.getText("immutable/ep3.txt")).thenReturn(content);
        when(storage.contentHash(content)).thenReturn("a".repeat(64));
        currentFacts(List.of(fact));
        capture = new CharacterAnalysisStateSource(characters,sources,new CharacterSnapshotAccessor(),
                new CharacterAnalysisStateMapper(),candidates,factRepository,
                new org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver(storage,
                        new org.monitoring.catchholebackend.domain.analysis.processor.SettingBookAutomaticEvidenceValidator(storage), jdbc));
    }

    @Test @DisplayName("legacy 캐릭터의 빈 현재 상태도 미상 대표 시점을 명시하며 회차로 추정하지 않는다")
    void emptyCurrentStateKeepsExplicitUnknownNarrativeTime() {
        character.replaceCurrentSnapshots(null, null, null, null, null, null, null);
        currentFacts(List.of());
        var owner = owner();
        assertThat(owner.path("slots")).isEmpty();
        assertThat(owner.has("currentNarrativeTime")).isTrue();
        assertThat(owner.path("currentNarrativeTime").isNull()).isTrue();
    }

    @Test @DisplayName("회복으로 현재값이 비어도 서로 다른 STATUS 키의 이전 사실과 종료 사건이 동결된다")
    void emptyCurrentStatusRetainsBothEndedFactAndRemoveEventAcrossKeys() {
        character.replaceCurrentSnapshots(null, null, null, null, null, null, null);
        currentFacts(List.of());
        CharacterFact ended = CharacterFact.create(character, candidate, CharacterFactType.STATUS,
                "status.검지_경직", "경직", "경직", json.createObjectNode().put("active", true),
                episode, null, origin, BigDecimal.ONE, 3);
        ReflectionTestUtils.setField(ended, "id", UUID.randomUUID());
        SettingCandidate recovery = SettingCandidate.create(work, episode, candidate.getSourceChunkId(), origin,
                SettingEntityType.CHARACTER, "한서린", "status.회복", "회복", SettingValueType.JSON,
                json.createObjectNode().put("active", false), candidate.getEvidenceSpans().deepCopy(),
                BigDecimal.ONE, json.createObjectNode());
        ReflectionTestUtils.setField(recovery, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(recovery, "matchedCharacterId", character.getId());
        ReflectionTestUtils.setField(recovery, "suggestedOperation",
                org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation.REMOVE);
        ReflectionTestUtils.setField(recovery, "temporalScope",
                org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope.PRESENT);
        ReflectionTestUtils.setField(recovery, "removedSnapshotEntriesJson", json.createArrayNode().add(
                json.createObjectNode().put("factType", "STATUS").put("factKey", "status.검지_경직")));
        CharacterFact removed = CharacterFact.create(character, recovery, CharacterFactType.STATUS,
                "status.회복", "회복", "회복", recovery.getValueJson(), episode, null, origin, BigDecimal.ONE, 3);
        ReflectionTestUtils.setField(removed, "id", UUID.randomUUID());
        when(factRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .thenReturn(List.of(removed, ended));

        var frozen = owner();
        assertThat(frozen.path("slots")).isEmpty();
        var history = frozen.path("historyFacts");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("operation").asText()).isEqualTo("REMOVE");
        assertThat(history.get(0).path("removedSnapshotEntries").get(0).path("factKey").asText())
                .isEqualTo("status.검지_경직");
        assertThat(history.get(1).path("factKey").asText()).isEqualTo("status.검지_경직");
        assertThat(history.get(0).path("supportingSource").path("availability").asText()).isEqualTo("VERIFIED");
        assertThat(history.get(0).path("supportingSource").path("evidenceSpans").get(0).path("quote").asText())
                .isEqualTo(content);
    }

    @Test @DisplayName("대표 시점만 변경되어도 빈 현재 상태의 version/hash가 바뀌고 원 후보 근거를 고정한다")
    void timeOnlyChangeInvalidatesFrozenHashAndKeepsRepresentativeWithoutCurrentFacts() {
        character.replaceCurrentSnapshots(null, null, null, null, null, null, null);
        currentFacts(List.of());
        var before = owner();
        long version = character.getSnapshotVersion();
        ObjectNode point = json.createObjectNode().put("description", "장마 첫날");
        point.set("evidenceSpans", json.createArrayNode().add(json.createObjectNode().put("quote", content)
                .put("startOffset", 0).put("endOffset", content.codePointCount(0, content.length()))
                .put("sourceChunkId", candidate.getSourceChunkId().toString())));
        candidate.recordNarrativeTime(json.createObjectNode().putNull("factTime").set("referenceTime", point));
        when(candidates.findByIdAndWorkId(candidate.getId(), work.getId())).thenReturn(java.util.Optional.of(candidate));
        org.mockito.Mockito.doReturn(List.<Object[]>of(new Object[]{0, content.codePointCount(0, content.length()), content}))
                .when(jdbc).query(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),
                        org.mockito.ArgumentMatchers.any(Object[].class));
        ObjectNode selected = point.deepCopy().put("sourceCandidateId", candidate.getId().toString());
        assertThat(character.updateCurrentNarrativeTime(selected, true)).isTrue();
        var frozen = owner();
        assertThat(character.getSnapshotVersion()).isEqualTo(version + 1);
        assertThat(new AnalysisStateJournal().hash(frozen)).isNotEqualTo(new AnalysisStateJournal().hash(before));
        assertThat(frozen.path("slots")).isEmpty();
        assertThat(frozen.path("currentNarrativeTime").path("description").asText()).isEqualTo("장마 첫날");
        assertThat(frozen.path("currentNarrativeTime").path("supportingSource").path("availability").asText()).isEqualTo("VERIFIED");
        selected.put("description", "이후 변경");
        assertThat(frozen.path("currentNarrativeTime").path("description").asText()).isEqualTo("장마 첫날");
        assertThat(character.updateCurrentNarrativeTime(character.getCurrentNarrativeTime(), true)).isFalse();
        assertThat(character.getSnapshotVersion()).isEqualTo(version + 1);
    }

    @Test @DisplayName("확정 Fact는 사실 시점과 관측 시점을 분리 복사하여 이후 후보 변경과 독립된다")
    void confirmedFactCaptureKeepsSeparateCopiedTimesAfterCandidateMutation() {
        ObjectNode accident = json.createObjectNode().put("description", "208년 사고");
        accident.set("evidenceSpans", json.createArrayNode().add(json.createObjectNode().put("quote", content)
                .put("startOffset", 0).put("endOffset", content.codePointCount(0, content.length()))
                .put("sourceChunkId", candidate.getSourceChunkId().toString())));
        ObjectNode observation = accident.deepCopy().put("description", "장마 첫날 관측");
        candidate.recordNarrativeTime(json.createObjectNode().set("factTime", accident));
        ((ObjectNode)candidate.getNarrativeTime()).set("referenceTime", observation);
        CharacterFact copied = CharacterFact.create(character, candidate, CharacterFactType.PROFILE,
                "profile.오른손_감각", "정상", "정상", candidate.getValueJson(), episode, null, origin, BigDecimal.ONE, 3);
        currentFacts(List.of(copied));
        ((ObjectNode)candidate.getNarrativeTime().path("factTime")).put("description", "후보의 이후 변경");
        org.mockito.Mockito.doReturn(List.<Object[]>of(new Object[]{0, content.codePointCount(0, content.length()), content}))
                .when(jdbc).query(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),
                        org.mockito.ArgumentMatchers.any(Object[].class));

        var source = owner().path("slots").path(key).path("supportingSources").get(0);

        assertThat(source.path("narrativeTime").path("factTime").path("description").asText()).isEqualTo("208년 사고");
        assertThat(source.path("narrativeTime").path("referenceTime").path("description").asText()).isEqualTo("장마 첫날 관측");
        assertThat(source.path("source").path("episodeNo").asInt()).isEqualTo(3);
    }

    @Test @DisplayName("alias 추출 키가 달라도 확정 Fact canonical 슬롯에 모든 원문 근거가 결속된다")
    void freezesCanonicalFactQuoteAndManifest() {
        var owner = owner();
        assertThat(owner.path("currentSupportingEvidenceVersion").asInt()).isEqualTo(1);
        var row = owner.path("slots").path(key).path("supportingSources").get(0);
        assertThat(row.path("availability").asText()).isEqualTo("VERIFIED");
        assertThat(row.path("sourceCandidateId").asText()).isEqualTo(candidate.getId().toString());
        assertThat(row.path("sourceAnalysisJobId").asText()).isEqualTo(origin.getId().toString());
        assertThat(row.path("source").path("sourceKind").asText()).isEqualTo("EPISODE");
        assertThat(row.path("source").path("episodeNo").asInt()).isEqualTo(3);
        assertThat(row.path("source").path("contentHash").asText()).isEqualTo("a".repeat(64));
        assertThat(row.path("evidenceSpans").get(0).path("quote").asText()).isEqualTo(content);
        assertThat(row.path("evidenceSpans").get(0).path("endOffset").asInt())
                .isEqualTo(content.codePointCount(0,content.length()));
    }

    @Test @DisplayName("옛 청크가 재생성되어도 현재 Fact·과거 이력·대표 시점을 같은 고정 원문으로 동결한다")
    void rechunkedSourceKeepsCurrentHistoryAndRepresentativeTimeAvailable() {
        ObjectNode point = json.createObjectNode().put("description", "회복한 날");
        point.set("evidenceSpans", json.createArrayNode().add(json.createObjectNode().put("quote", content)
                .put("startOffset", 0).put("endOffset", content.codePointCount(0, content.length()))
                .put("sourceChunkId", candidate.getSourceChunkId().toString())));
        candidate.recordNarrativeTime(json.createObjectNode().putNull("factTime").set("referenceTime", point));
        ReflectionTestUtils.setField(origin, "status", org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED);
        var current = CharacterFact.create(character, candidate, CharacterFactType.PROFILE, "profile.오른손_감각",
                "정상", "정상", candidate.getValueJson(), episode, null, origin, BigDecimal.ONE, 3);
        var historical = CharacterFact.create(character, candidate, CharacterFactType.PROFILE, "profile.관측",
                "회복 관측", "회복 관측", candidate.getValueJson(), episode, null, origin, BigDecimal.ONE, 3);
        ReflectionTestUtils.setField(current, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(historical, "id", UUID.randomUUID());
        currentFacts(List.of(current));
        when(factRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId())).thenReturn(List.of(current, historical));
        when(candidates.findByIdAndWorkId(candidate.getId(), work.getId())).thenReturn(java.util.Optional.of(candidate));
        org.mockito.Mockito.doReturn(0).when(jdbc).queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class), org.mockito.ArgumentMatchers.any(Object[].class));
        character.updateCurrentNarrativeTime(point.deepCopy().put("sourceCandidateId", candidate.getId().toString()), true);

        var frozen = owner();

        assertThat(frozen.path("slots").path(key).path("supportingSources").get(0).path("availability").asText()).isEqualTo("VERIFIED");
        assertThat(frozen.path("historyFacts")).hasSize(1);
        assertThat(frozen.path("historyFacts").get(0).path("narrativeTime").path("referenceTime")).isEqualTo(point);
        assertThat(frozen.path("currentNarrativeTime").path("supportingSource").path("availability").asText()).isEqualTo("VERIFIED");
    }

    @Test @DisplayName("원문 파기 후에도 확정값과 시점 설명은 남기고 사용할 수 없는 인용만 비운다")
    void purgedSourceRetainsConfirmedStateWithoutReadingDeletedText() {
        ObjectNode point = json.createObjectNode().put("description", "회복한 날");
        point.set("evidenceSpans", json.createArrayNode().add(json.createObjectNode().put("quote", content)
                .put("startOffset", 0).put("endOffset", content.codePointCount(0, content.length()))
                .put("sourceChunkId", candidate.getSourceChunkId().toString())));
        candidate.recordNarrativeTime(json.createObjectNode().putNull("factTime").set("referenceTime", point));
        candidate.purgeSourceEvidence();
        character.updateCurrentNarrativeTime(org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.purgeEvidence(
                point.deepCopy().put("sourceCandidateId", candidate.getId().toString())), true);
        when(candidates.findByIdAndWorkId(candidate.getId(), work.getId())).thenReturn(java.util.Optional.of(candidate));

        var frozen = owner();

        assertThat(frozen.path("slots").path(key).path("factValue").asText()).isEqualTo("정상");
        assertThat(frozen.path("currentNarrativeTime").path("description").asText()).isEqualTo("회복한 날");
        assertThat(frozen.path("currentNarrativeTime").path("evidenceSpans")).isEmpty();
        assertThat(frozen.path("currentNarrativeTime").path("supportingSource").path("availability").asText()).isEqualTo("UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    @Test @DisplayName("capture 뒤 후보 인용과 현재 episode 원문이 바뀌어도 기존 입력 bytes는 바뀌지 않는다")
    void frozenQuoteDoesNotFollowLiveCandidateOrEpisode() {
        var before = owner();
        String hash = new AnalysisStateJournal().hash(before);
        ((ObjectNode)candidate.getEvidenceSpans().get(0)).put("quote","live changed");
        episode.updateContentStorage("latest/ep3.txt",null,"b".repeat(64));
        assertThat(before.path("slots").path(key).path("supportingSources").get(0).path("evidenceSpans").get(0).path("quote").asText())
                .isEqualTo(content);
        assertThat(new AnalysisStateJournal().hash(before)).isEqualTo(hash);
    }

    @Test @DisplayName("합쳐진 CURRENT의 두 실제 source는 대표 하나로 축약되지 않는다")
    void retainsAllAppliedOrigins() {
        SettingCandidate second = SettingCandidate.create(work,episode,null,origin,SettingEntityType.CHARACTER,
                "한서린","profile.오른손_감각","회복",SettingValueType.STRING,null,candidate.getEvidenceSpans().deepCopy(),
                BigDecimal.ONE,json.createObjectNode());
        ReflectionTestUtils.setField(second,"id",UUID.randomUUID());
        ReflectionTestUtils.setField(second,"matchedCharacterId",character.getId());
        currentFacts(List.of(fact,CharacterFact.create(character,second,CharacterFactType.PROFILE,
                "profile.오른손_감각","회복","회복",null,episode,null,origin,BigDecimal.ONE,3)));
        var rows = owner().path("slots").path(key).path("supportingSources");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("sourceCandidateId").asText()).isEqualTo(candidate.getId().toString());
        assertThat(rows.get(1).path("sourceCandidateId").asText()).isEqualTo(second.getId().toString());
    }

    @Test @DisplayName("수동 CURRENT와 legacy 출처 없음은 인용을 발명하지 않고 명시 unavailable로 남긴다")
    void manualCurrentHasExplicitUnavailableEvidence() {
        currentFacts(List.of(CharacterFact.createManual(character,CharacterFactType.PROFILE,"profile.오른손_감각","작가값",null)));
        ObjectNode profile = json.createObjectNode();
        profile.putObject("profile.오른손_감각").put("value", "작가값");
        character.replaceCurrentSnapshots(null, null, profile, null, null, null, null);
        var slot = owner().path("slots").path(key);
        assertThat(slot.path("factValue").asText()).isEqualTo("작가값");
        assertThat(slot.path("provenance").path("currentValueProtected").asBoolean()).isTrue();
        assertThat(slot.path("supportingSources").get(0).path("availability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(slot.path("supportingSources").get(0).path("evidenceSpans")).isEmpty();
        assertThat(slot.path("supportingSources").get(0).path("sourceCandidateId").isNull()).isTrue();
    }

    @Test @DisplayName("명시한 인용이 원문에 없으면 legacy fallback으로 capture하지 않는다")
    void rejectsForgedQuote() {
        ((ObjectNode)candidate.getEvidenceSpans().get(0)).put("quote","원문에 없는 회복");
        assertThatThrownBy(this::owner).isInstanceOf(RuntimeException.class);
    }

    @Test @DisplayName("원 storage bytes hash가 다르면 정상 unavailable로 낮추지 않는다")
    void rejectsOriginalSourceHashMismatch() {
        when(storage.contentHash(content)).thenReturn("f".repeat(64));
        assertThatThrownBy(this::owner).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("다른 작품 origin manifest는 실제 슬롯 source로 허용하지 않는다")
    void rejectsForeignOriginWork() {
        Work foreign = Work.create(work.getMember(),"다른 작품",WorkGenre.FANTASY,"");
        ReflectionTestUtils.setField(foreign,"id",UUID.randomUUID());
        ReflectionTestUtils.setField(origin,"work",foreign);
        assertThatThrownBy(this::owner).isInstanceOf(RuntimeException.class);
    }

    @Test @DisplayName("실제 legacy Fact와 후보에 원 job이 모두 없으면 명시 unavailable이며 live 원문을 보충하지 않는다")
    void genuineLegacyOriginWithoutJobIsUnavailable() {
        ReflectionTestUtils.setField(fact, "extractedByJob", null);
        ReflectionTestUtils.setField(candidate, "analysisJob", null);
        var row = owner().path("slots").path(key).path("supportingSources").get(0);
        assertThat(row.path("sourceCandidateId").asText()).isEqualTo(candidate.getId().toString());
        assertThat(row.path("sourceAnalysisJobId").isNull()).isTrue();
        assertThat(row.path("unavailableReason").asText()).isEqualTo("LEGACY_NO_MANIFEST");
        assertThat(row.path("evidenceSpans")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    @Test @DisplayName("BOOK origin의 이전 revision 근거는 최신 저장과 archive 뒤에도 원 revision과 기준 시점을 유지한다")
    void bookOriginUsesOriginalRevisionAfterEditAndArchive() {
        var batch = org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work, work.getMember(),
                org.monitoring.catchholebackend.domain.upload.type.UploadType.SETTING_BOOK,
                org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.TEXT_PASTE);
        var book = org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(batch,
                org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK, "회복 설정집.txt", "text/plain", "s3://raw/book", 10);
        ReflectionTestUtils.setField(book, "id", UUID.randomUUID());
        var revision = org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book, 1,
                new org.monitoring.catchholebackend.global.storage.StoredTextObject("immutable/book-r1.txt", "version-r1", "c".repeat(64), content.codePointCount(0, content.length())),
                org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.EPISODE, 3);
        ReflectionTestUtils.setField(revision, "id", UUID.randomUUID());
        book.linkCurrentRevision(revision);
        var bookJob = AnalysisJob.createSettingBook(work, book, revision);
        ReflectionTestUtils.setField(bookJob, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(candidate, "analysisJob", bookJob);
        ReflectionTestUtils.setField(candidate, "episode", null);
        ReflectionTestUtils.setField(fact, "extractedByJob", bookJob);
        ReflectionTestUtils.setField(fact, "sourceEpisode", null);
        when(storage.getText("immutable/book-r1.txt")).thenReturn(content);
        when(storage.contentHash(content)).thenReturn("c".repeat(64));
        var next = org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book, 2,
                new org.monitoring.catchholebackend.global.storage.StoredTextObject("latest/book-r2.txt", null, "d".repeat(64), 10));
        ReflectionTestUtils.setField(next, "id", UUID.randomUUID());
        book.linkCurrentRevision(next);
        book.archive();
        var row = owner().path("slots").path(key).path("supportingSources").get(0);
        assertThat(row.path("availability").asText()).isEqualTo("VERIFIED");
        assertThat(row.path("source").path("sourceKind").asText()).isEqualTo("SETTING_BOOK");
        assertThat(row.path("source").path("episodeId").isNull()).isTrue();
        assertThat(row.path("source").path("episodeNo").isNull()).isTrue();
        assertThat(row.path("source").path("revisionId").asText()).isEqualTo(revision.getId().toString());
        assertThat(row.path("source").path("referenceEpisodeNo").asInt()).isEqualTo(3);
        assertThat(row.path("storageBinding").path("key").asText()).isEqualTo("immutable/book-r1.txt");
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).getText("latest/book-r2.txt");
    }

    private com.fasterxml.jackson.databind.JsonNode owner() {
        return capture.capture(work).path("character:"+character.getId());
    }
    private void currentFacts(List<CharacterFact> facts) {
        java.util.ArrayList<CharacterSnapshotSource> links = new java.util.ArrayList<>();
        for(int i=0;i<facts.size();i++) links.add(CharacterSnapshotSource.create(character,CharacterFactType.PROFILE,
                "profile.오른손_감각",facts.get(i),i));
        when(sources.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(character.getId())).thenReturn(links);
    }
}
