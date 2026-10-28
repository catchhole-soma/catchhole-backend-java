package org.monitoring.catchholebackend.domain.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

/** Real H2 HTTP/job/token flows; only immutable object storage is replaced by the inherited mock. */
class FrozenCurrentCaptureFailureIntegrationTest extends SettingBookAnalysisIntegrationTest {
    @Test
    @DisplayName("확정값의 일반 인용만 확인 불가이면 HTTP claim·문맥·정상 검토 저장을 계속하고 기존 Fact와 인용은 고치지 않는다")
    void retainedQuoteOnlyMismatchCreatesContextAndSavesReview() throws Exception {
        String incoming = "한서린의 오른손은 저리다.";
        var book = upload(incoming);
        var pending = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        var retained = currentQuote(false);
        var oldEvidence = retained.getEvidenceSpans().deepCopy();
        var owner = em.find(WorkCharacter.class, retained.getMatchedCharacterId());
        em.flush();

        var claimed = evidenceClaim("SETTING_BOOK", 200);
        UUID lease = UUID.fromString(claimed.path("leaseToken").asText());
        var frozen = pending.getAutomaticInputState().path("characters").path("character:" + owner.getId());
        var frozenSlot = frozen.path("slots").path("PROFILE:profile.오른손_감각");
        assertThat(frozenSlot.path("factValue").asText()).isEqualTo("정상");
        assertThat(frozenSlot.path("supportingSources").get(0).path("unavailableReason").asText()).isEqualTo("QUOTE_NOT_FOUND");
        assertThat(frozenSlot.path("supportingSources").get(0).path("evidenceSpans")).isEmpty();
        assertThat(pending.getInputStateHash()).hasSize(64);
        em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema.create(work,
                "profile.오른손_감각", null, "오른손 감각", CharacterFactType.PROFILE, SettingValueType.STRING,
                org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics.BASE_VALUE,
                org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy.REPLACE, json.createArrayNode(),
                org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource.SYSTEM_SEED, true));
        var candidate = SettingCandidate.create(work, null, null, pending, SettingEntityType.CHARACTER,
                "한서린", "한서린", owner.getId(), org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED,
                "profile.오른손_감각", "저림", SettingValueType.STRING, json.createObjectNode().put("value", "저림"),
                json.createArrayNode().add(json.createObjectNode().put("quote", incoming).put("startOffset", 0)
                        .put("endOffset", incoming.codePointCount(0, incoming.length()))), BigDecimal.ONE, json.createObjectNode());
        em.persist(candidate);
        pending.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
        em.flush();
        String route = "/api/internal/v1/analysis-jobs/" + pending.getId() + "/character-fact-comparison-batches";
        JsonNode batch = response(mvc.perform(post(route + "/claim-next").header("X-Internal-Api-Key", "test-internal-api-key")
                .header("X-Worker-Lease-Token", lease).header("X-Character-Comparison-Person-Batches", true))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String batchRoute = route + "/" + batch.path("comparisonBatchId").asText();
        JsonNode context = response(mvc.perform(post(batchRoute + "/context").header("X-Internal-Api-Key", "test-internal-api-key")
                .header("X-Worker-Lease-Token", lease)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var current = context.path("advisoryCurrentContext").path("currentSlots").get(0);
        assertThat(current.path("factValue").asText()).isEqualTo("정상");
        assertThat(current.path("supportingSources").get(0).path("availability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(current.path("supportingSources").get(0).path("evidenceSpans")).isEmpty();
        ObjectNode decision = json.createObjectNode().put("candidateRef", "C1").put("operation", "REVIEW_REQUIRED")
                .put("resolvedCanonicalFactKey", "profile.오른손_감각").putNull("targetSnapshotRef")
                .putNull("proposedFactValue").putNull("proposedValueJson").put("temporalScope", "PRESENT")
                .put("comparisonReason", "새 관측 근거가 부족해 확인이 필요합니다.").put("advanceCurrentNarrativeTime", false);
        decision.putArray("removedSnapshotRefs"); decision.putArray("dependencyCandidateRefs");
        ObjectNode complete = json.createObjectNode().put("contextToken", context.path("contextToken").asText());
        complete.putArray("decisions").add(decision); complete.putArray("failures");
        mvc.perform(post(batchRoute + "/complete").header("X-Internal-Api-Key", "test-internal-api-key")
                .header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(complete)))
                .andExpect(status().isOk());
        em.flush();
        assertThat(candidate.getComparisonStatus().name()).isEqualTo("COMPLETED");
        assertThat(candidate.getSuggestedOperation().name()).isEqualTo("REVIEW_REQUIRED");
        assertThat(candidate.getComparisonFailureCode()).isNull();
        assertThat(pending.getStatus().name()).isEqualTo("RUNNING");
        assertThat(pending.getFailureCode()).isNull();
        assertThat(retained.getReviewStatus().name()).isEqualTo("CONFIRMED");
        assertThat(retained.getEvidenceSpans()).isEqualTo(oldEvidence);
        assertThat(sql.queryForObject("select count(*) from character_facts where character_id=?", Integer.class, owner.getId())).isEqualTo(1);
        assertThat(sql.queryForObject("select fact_value from character_facts where character_id=?", String.class, owner.getId())).isEqualTo("정상");
    }

    @Test
    @DisplayName("잘못된 CURRENT 인용의 첫 capture는 작업 실패·고아 예약 정리 후 다른 작품의 정상 claim을 허용한다")
    void invalidOldestCaptureFailsWithoutQueueOrTokenLeak() throws Exception {
        var book = upload("다음 분석 원문");
        var pending = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        invalidCurrentQuote();
        // A reservation left by an interrupted old worker must not survive a failed new capture.
        UUID reservation = UUID.randomUUID();
        var account = em.createQuery("select a from AiTokenAccount a where a.member.id=:id", AiTokenAccount.class)
                .setParameter("id", work.getMember().getId()).getSingleResult();
        account.reserve(1000);
        em.persist(AiTokenUsage.reserve(reservation, pending, AiTokenPurpose.SETTING_EXTRACTION, 1, "offline-test", 1000));
        em.flush();
        evidenceClaim("SETTING_BOOK", 204);
        em.flush();
        assertThat(pending.getStatus().name()).isEqualTo("FAILED");
        assertThat(pending.getJournalStatus().name()).isEqualTo("INCOMPLETE");
        assertThat(pending.getErrorMessage()).startsWith("FROZEN_CURRENT_EVIDENCE_INVALID");
        assertThat(pending.getLeaseToken()).isNull();
        assertThat(pending.getLeaseExpiresAt()).isNull();
        assertThat(sql.queryForObject("select count(*) from setting_candidates where analysis_job_id=?", Integer.class, pending.getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=?", Integer.class, pending.getId())).isZero();
        assertThat(sql.queryForObject("select status from ai_token_usages where request_id=?", String.class, reservation)).isEqualTo("RELEASED");
        assertThat(sql.queryForObject("select outcome from ai_token_usages where request_id=?", String.class, reservation)).isEqualTo("USAGE_UNAVAILABLE");
        assertThat(sql.queryForObject("select reserved_tokens+used_tokens from ai_token_accounts where member_id=?", Long.class, work.getMember().getId())).isZero();
        work = works.save(Work.create(work.getMember(), "다음 정상 작품", WorkGenre.FANTASY, null));
        var valid = upload("독립 작품의 정상 원문");
        var claimed = evidenceClaim("SETTING_BOOK", 200);
        assertThat(claimed.path("analysisJobId").asText()).isEqualTo(valid.path("latestAnalysisJob").path("id").asText());
        assertThat(claimed.path("comparisonEvidenceContract").path("formatVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("capture 실패한 선행 회차를 같은 실행의 후속 회차가 우회하지 않는다")
    void invalidPredecessorDoesNotUnlockOrderedSuccessor() throws Exception {
        AnalysisJob first = pendingEpisode(2), successor = pendingEpisode(3);
        states.initializeRun(List.of(first, successor));
        invalidCurrentQuote();
        em.flush();
        evidenceClaim("EPISODE", 204);
        assertThat(first.getStatus().name()).as("journal=%s step=%s sourceCurrent=%s", first.getJournalStatus(), first.getCurrentStep(), first.hasCurrentSourceVersion()).isEqualTo("FAILED");
        assertThat(first.getJournalStatus().name()).isEqualTo("INCOMPLETE");
        evidenceClaim("EPISODE", 204);
        assertThat(successor.getStatus().name()).isEqualTo("PENDING");
        assertThat(successor.getLeaseToken()).isNull();
        assertThat(successor.getInputStateHash()).isNull();
        assertThat(successor.getPredecessorJobId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("고정 원 storage bytes를 검증할 수 없는 기술 실패는 영구 근거 실패로 낮추지 않는다")
    void sourceStorageHashFailureKeepsRetryBoundary() throws Exception {
        var book = upload("기술 실패 뒤 재시도할 원문");
        var pending = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        currentQuote(true);
        em.flush();
        evidenceClaim("SETTING_BOOK", 500);
        assertThat(pending.getStatus().name()).isEqualTo("PENDING");
        assertThat(pending.getJournalStatus().name()).isEqualTo("PENDING");
        assertThat(pending.getLeaseToken()).isNull();
        assertThat(pending.getFailureCode()).isNull();
        assertThat(pending.getAutomaticInputState()).isNull();
        assertThat(sql.queryForObject("select reserved_tokens+used_tokens from ai_token_accounts where member_id=?", Long.class, work.getMember().getId())).isZero();
    }

    @Test
    @DisplayName("이번 원문 출처가 보관된 stale 작업은 기존 취소 경계이며 근거 오류 FAILED로 바꾸지 않는다")
    void archivedSourceKeepsExistingCancellationBoundary() throws Exception {
        var book = upload("보관할 원문");
        var pending = jobs.findById(UUID.fromString(book.path("latestAnalysisJob").path("id").asText())).orElseThrow();
        em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadFile.class, UUID.fromString(book.path("id").asText())).archive();
        em.flush();
        evidenceClaim("SETTING_BOOK", 204);
        assertThat(pending.getStatus().name()).isEqualTo("CANCELED");
        assertThat(pending.getJournalStatus().name()).isEqualTo("INVALIDATED");
        assertThat(pending.getLeaseToken()).isNull();
        assertThat(pending.getFailureCode()).isNull();
    }

    private AnalysisJob pendingEpisode(int number) throws Exception {
        String content = number+"화의 정상 원문";
        String key = "synthetic/next-"+number;
        objects.put(key, content.getBytes(StandardCharsets.UTF_8));
        var episode = Episode.create(work, null, number, number+"화", key, "v1", hash(content), content.length());
        em.persist(episode);
        var batch = org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work, work.getMember(),
                org.monitoring.catchholebackend.domain.upload.type.UploadType.SINGLE_EPISODE,
                org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.FILE);
        em.persist(batch);
        var job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        // Run identity/mode columns are immutable: initialize the run before its first insert.
        return job;
    }

    private void invalidCurrentQuote() throws Exception {
        var candidate = currentQuote(false);
        ((ObjectNode)candidate.getEvidenceSpans().get(0)).put("start_offset", 0).put("end_offset", 1);
    }

    private SettingCandidate currentQuote(boolean technicalFailure) throws Exception {
        String original = "오른손의 감각은 회복되어 정상이다.";
        String key = "synthetic/original-recovery";
        objects.put(key, original.getBytes(StandardCharsets.UTF_8));
        var episode = Episode.create(work, null, 1, "원 근거", key, "v1", hash(original), original.length());
        em.persist(episode);
        var origin = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        em.persist(origin);
        origin.succeed("{}", 0, 0);
        var owner = WorkCharacter.create(work, "한서린", null, null, null, null, null, null, null, null, null);
        var profile = json.createObjectNode(); profile.putObject("profile.오른손_감각").put("value", "정상");
        owner.replaceCurrentSnapshots(null, null, profile, null, null, null, null);
        em.persist(owner);
        var candidate = SettingCandidate.create(work, episode, null, origin, SettingEntityType.CHARACTER, "한서린",
                "profile.손감각", "정상", SettingValueType.STRING, json.createObjectNode().put("value", "정상"),
                json.createArrayNode().add(json.createObjectNode().put("quote", technicalFailure ? original : "원문에 존재하지 않는 인용").putNull("start_offset").putNull("end_offset")),
                BigDecimal.ONE, json.createObjectNode());
        ReflectionTestUtils.setField(candidate, "matchedCharacterId", owner.getId());
        candidate.confirm();
        em.persist(candidate);
        var fact = CharacterFact.create(owner, candidate, CharacterFactType.PROFILE, "profile.오른손_감각", "정상", "정상",
                json.createObjectNode().put("value", "정상"), episode, null, origin, BigDecimal.ONE, 1);
        em.persist(fact);
        em.persist(CharacterSnapshotSource.create(owner, CharacterFactType.PROFILE, "profile.오른손_감각", fact, 0));
        if (technicalFailure) objects.put(key, "고정 hash와 다른 storage bytes".getBytes(StandardCharsets.UTF_8));
        return candidate;
    }

    private String hash(String content) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode evidenceClaim(String source, int expectedStatus) throws Exception {
        String result = mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key", "test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "allowedJobTypes", List.of("SETTING_EXTRACTION"), "supportedAnalysisModes", List.of("ORDERED_PROVISIONAL"),
                        "supportedSourceKinds", List.of(source), "supportsSettingBookReferenceTime", true,
                        "supportsSemanticCurrentContext", true, "supportsFrozenComparisonEvidence", true))))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return result.isBlank() ? json.createObjectNode() : json.readTree(result).path("data");
    }
}
