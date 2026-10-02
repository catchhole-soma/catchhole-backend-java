package org.monitoring.catchholebackend.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 명시적으로 실행할 때만 켜지는 합성 데이터 브라우저 검증용 서버. 운영/로컬 DB에 연결하지 않는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.config.import=", "server.port=18081", "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:gh215-review-runtime;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "cors.allowed-origins=http://localhost:3100,http://127.0.0.1:3100,http://localhost:3000"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "GH215_REVIEW_RUNTIME", matches = "true")
@DisplayName("합성 후보 데이터로 검토 화면 브라우저 검증 서버를 실행한다")
class ReviewUxHttpRuntimeTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtTokenProvider tokens;
    @Autowired PasswordEncoder passwords;

    @Test
    @DisplayName("테스트용 주소와 인증 정보를 로컬 파일에만 쓰고 종료 신호까지 서버를 유지한다")
    void serveSyntheticReviewCases() throws Exception {
        Path output = Path.of("/tmp/gh215-review-runtime.json");
        Path stop = Path.of("/tmp/gh215-review-runtime.stop");
        Files.deleteIfExists(stop);
        Map<String, Object> fixture = new TransactionTemplate(transactions).execute(status -> seed());
        Files.writeString(output, new ObjectMapper().writeValueAsString(fixture));
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"));
        long deadline = System.currentTimeMillis() + 30 * 60 * 1000;
        while (!Files.exists(stop) && System.currentTimeMillis() < deadline) Thread.sleep(500);
    }

    private Map<String, Object> seed() {
        String password = UUID.randomUUID().toString();
        Member member = Member.registerEmailVerified("review-ux@example.test", passwords.encode(password),
                "검토 시안 검증", LocalDateTime.now());
        entities.persist(member);
        Work work = Work.create(member, "검토 화면 검증용 작품", WorkGenre.FANTASY, "합성 데이터만 사용하는 검증 작품");
        entities.persist(work);
        UploadBatch batch = UploadBatch.create(work, member, UploadType.INITIAL_IMPORT, UploadSourceType.FILE);
        entities.persist(batch);
        Episode episode = Episode.create(work, null, 3, "3화 검토 예시", "synthetic/source", "v1", "synthetic-hash", 100);
        entities.persist(episode);
        AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        ReflectionTestUtils.setField(job, "journalStatus", AnalysisJournalStatus.SEALED);
        ReflectionTestUtils.setField(job, "automaticAppliedAt", LocalDateTime.now());
        ReflectionTestUtils.setField(job, "sourceEpisodeNo", episode.getEpisodeNo());
        ReflectionTestUtils.setField(job, "sourceContentHash", episode.getContentHash());
        ReflectionTestUtils.setField(job, "sourceContentS3Key", episode.getContentS3Key());
        ReflectionTestUtils.setField(job, "sourceContentS3Version", episode.getContentS3Version());
        entities.persist(job);
        schema("age", "나이", CharacterFactType.AGE, SettingValueType.NUMBER);
        schema("profile.affiliation", "소속", CharacterFactType.PROFILE, SettingValueType.STRING);
        schema("profile.race", "종족", CharacterFactType.PROFILE, SettingValueType.STRING);
        WorkCharacter knight = WorkCharacter.create(work, "루안 베른", "북부 기사", 20, null,
                JSON.objectNode().set("profile.affiliation", JSON.objectNode().put("value", "북부 기사단")), null, null, null, null, episode.getId());
        entities.persist(knight);
        WorkCharacter merchant = WorkCharacter.create(work, "루안 케르", "왕도 상인", 26, null,
                null, null, null, null, null, episode.getId());
        entities.persist(merchant);
        SettingCandidate meaning = characterCandidate(work, episode, job, knight, "루안 베른", "profile.affiliation", "왕실 기사단",
                SettingValueType.STRING, CharacterFactOperation.REVIEW_REQUIRED, CharacterFactTemporalScope.UNKNOWN);
        SettingCandidate normal = characterCandidate(work, episode, job, merchant, "루안 케르", "profile.race", "인간",
                SettingValueType.STRING, CharacterFactOperation.ADD, CharacterFactTemporalScope.PRESENT);
        SettingCandidate unknown = characterCandidate(work, episode, job, null, "인물 미상", "age", "20",
                SettingValueType.NUMBER, CharacterFactOperation.REVIEW_REQUIRED, CharacterFactTemporalScope.UNKNOWN);
        ReflectionTestUtils.setField(unknown, "matchStatus", SettingCandidateMatchStatus.AMBIGUOUS);

        WorldSetting maze = WorldSetting.create(work, WorldSettingCategory.LOCATION, "별빛 미궁", "탐사 규칙", "귀환 조건", "해가 지기 전에 귀환해야 한다.");
        entities.persist(maze);
        WorldSetting royal = WorldSetting.create(work, WorldSettingCategory.LOCATION, "왕도 미궁", "위치", "왕도 지하에 있다.");
        entities.persist(royal);
        // 현재값이 직접 입력/출처 불명으로 보호되지 않도록 실제 앞 회차의 확정 근거를 함께 둔다.
        Episode priorEpisode = Episode.create(work, null, 1, "1화 기존 근거", "synthetic/prior-source", "v1", "prior-hash", 100);
        entities.persist(priorEpisode);
        AnalysisJob priorJob = AnalysisJob.create(work, batch, priorEpisode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(priorJob, "status", AnalysisJobStatus.SUCCEEDED);
        ReflectionTestUtils.setField(priorJob, "sourceEpisodeNo", 1);
        ReflectionTestUtils.setField(priorJob, "sourceContentHash", priorEpisode.getContentHash());
        ReflectionTestUtils.setField(priorJob, "sourceContentS3Key", priorEpisode.getContentS3Key());
        ReflectionTestUtils.setField(priorJob, "sourceContentS3Version", priorEpisode.getContentS3Version());
        entities.persist(priorJob);
        SettingCandidate priorCharacter = characterCandidate(work, priorEpisode, priorJob, knight, "루안 베른", "profile.affiliation",
                "북부 기사단", SettingValueType.STRING, CharacterFactOperation.ADD, CharacterFactTemporalScope.PRESENT);
        priorCharacter.confirm();
        priorCharacter.recordConfirmedApplicationMode(CharacterFactConfirmApplicationMode.APPLY_PROPOSAL);
        CharacterFact priorFact = CharacterFact.create(knight, priorCharacter, CharacterFactType.PROFILE, "profile.affiliation",
                "북부 기사단", "북부 기사단", JSON.objectNode().put("value", "북부 기사단"), priorEpisode, null, priorJob, BigDecimal.ONE, 1);
        entities.persist(priorFact);
        entities.persist(CharacterSnapshotSource.create(knight, CharacterFactType.PROFILE, "profile.affiliation", priorFact, 0));
        WorldSettingCandidate priorMaze = worldCandidate(work, priorEpisode, priorJob, maze, "별빛 미궁", "탐사 규칙", "귀환 조건",
                "해가 지기 전에 귀환해야 한다.", WorldSettingSuggestedOperation.ADD, null, null, null, null, WorldSettingConsolidationStatus.SINGLE);
        priorMaze.confirm(WorldSettingOperation.ADD, WorldSettingCategory.LOCATION, "별빛 미궁", "탐사 규칙", "귀환 조건",
                "해가 지기 전에 귀환해야 한다.", null, member, maze);
        WorldSettingCandidate priorRoyal = worldCandidate(work, priorEpisode, priorJob, royal, "왕도 미궁", null, "위치",
                "왕도 지하에 있다.", WorldSettingSuggestedOperation.ADD, null, null, null, null, WorldSettingConsolidationStatus.SINGLE);
        priorRoyal.confirm(WorldSettingOperation.ADD, WorldSettingCategory.LOCATION, "왕도 미궁", null, "위치",
                "왕도 지하에 있다.", null, member, royal);
        WorldSettingCandidate scope = worldCandidate(work, episode, job, maze, "별빛 미궁", null, "위험 기준", "체력이 5% 미만이면 귀환해야 한다.",
                WorldSettingSuggestedOperation.REVIEW_REQUIRED, WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED,
                "탐사 규칙", "귀환 조건", "해가 지기 전에 귀환해야 한다.", WorldSettingConsolidationStatus.SINGLE);
        WorldSettingCandidate added = worldCandidate(work, episode, job, null, "은빛 숲", null, "위치", "왕도 북쪽에 있다.",
                WorldSettingSuggestedOperation.ADD, null, null, null, null, WorldSettingConsolidationStatus.SINGLE);
        WorldSettingCandidate merged = worldCandidate(work, episode, job, royal, "왕도 미궁", null, "위치", "통행 허가가 필요하다.",
                WorldSettingSuggestedOperation.MERGE, null, null, "위치", "왕도 지하에 있다.", WorldSettingConsolidationStatus.SINGLE);
        ReflectionTestUtils.setField(merged, "proposedValue", "왕도 지하에 있으며 통행 허가가 필요하다.");
        UploadBatch manualBatch = UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE);
        entities.persist(manualBatch);
        Episode manualEpisode = Episode.create(work, null, 6, "6화 수동 검토", "synthetic/manual-source", "v1", "manual-hash", 100);
        entities.persist(manualEpisode);
        AnalysisJob manualJob = AnalysisJob.create(work, manualBatch, manualEpisode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(manualJob, "status", AnalysisJobStatus.SUCCEEDED);
        entities.persist(manualJob);
        WorkCharacter manualCharacter = WorkCharacter.create(work, "수동 검토 기사", null, null, null, null, null, null, null, null, manualEpisode.getId());
        entities.persist(manualCharacter);
        SettingCandidate manualFirst = characterCandidate(work, manualEpisode, manualJob, manualCharacter, "수동 검토 기사", "profile.affiliation", "왕실 기사단",
                SettingValueType.STRING, CharacterFactOperation.ADD, CharacterFactTemporalScope.PRESENT);
        SettingCandidate manualSecond = characterCandidate(work, manualEpisode, manualJob, manualCharacter, "수동 검토 기사", "profile.race", "인간",
                SettingValueType.STRING, CharacterFactOperation.MERGE, CharacterFactTemporalScope.PRESENT);
        ReflectionTestUtils.setField(manualSecond, "proposedFactValue", "인간이며 기사단의 정찰병이다.");
        ReflectionTestUtils.setField(manualSecond, "proposedValueJson", JSON.objectNode().put("value", "인간이며 기사단의 정찰병이다."));
        ReflectionTestUtils.setField(manualSecond, "comparisonDependencyCandidateIds", JSON.arrayNode().add(manualFirst.getId().toString()));
        SettingCandidate manualThird = characterCandidate(work, manualEpisode, manualJob, manualCharacter, "수동 검토 기사", "age", "20",
                SettingValueType.NUMBER, CharacterFactOperation.ADD, CharacterFactTemporalScope.PRESENT);
        entities.flush();
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("apiUrl", "http://127.0.0.1:18081");
        fixture.put("email", member.getEmail());
        fixture.put("password", password);
        fixture.put("accessToken", tokens.generateAccessToken(member));
        fixture.put("workId", work.getId());
        fixture.put("batchId", batch.getId());
        fixture.put("manualBatchId", manualBatch.getId());
        fixture.put("manualCharacterId", manualCharacter.getId());
        fixture.put("manualCandidateIds", Map.of("first", manualFirst.getId(), "second", manualSecond.getId(), "third", manualThird.getId()));
        fixture.put("characterIds", Map.of("knight", knight.getId(), "merchant", merchant.getId()));
        fixture.put("candidateIds", Map.of("meaning", meaning.getId(), "normal", normal.getId(), "unknown", unknown.getId(),
                "scope", scope.getId(), "added", added.getId(), "merged", merged.getId()));
        return fixture;
    }

    private void schema(String key, String name, CharacterFactType factType, SettingValueType type) {
        entities.persist(CharacterSettingSchema.create(null, key, null, name, factType, type,
                CharacterSettingValueSemantics.BASE_VALUE, CharacterSettingMergePolicy.REPLACE,
                JSON.arrayNode(), CharacterSettingSchemaSource.DEV_SEED, true));
    }

    private SettingCandidate characterCandidate(Work work, Episode episode, AnalysisJob job, WorkCharacter target,
            String name, String key, String value, SettingValueType type, CharacterFactOperation operation, CharacterFactTemporalScope scope) {
        var typed = JSON.objectNode();
        if (type == SettingValueType.NUMBER) typed.put("value", Integer.parseInt(value)); else typed.put("value", value);
        SettingCandidate candidate = SettingCandidate.create(work, episode, null, job, SettingEntityType.CHARACTER, name,
                name, target == null ? null : target.getId(), target == null ? SettingCandidateMatchStatus.UNRESOLVED : SettingCandidateMatchStatus.MATCHED,
                key, value, type, typed, JSON.arrayNode().add(JSON.objectNode().put("paragraph_index", 1).put("quote", name + "에 대한 설정: " + value)),
                BigDecimal.ONE, JSON.objectNode());
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(candidate, "suggestedOperation", operation);
        ReflectionTestUtils.setField(candidate, "temporalScope", scope);
        ReflectionTestUtils.setField(candidate, "proposedFactValue", value);
        ReflectionTestUtils.setField(candidate, "proposedValueJson", typed);
        ReflectionTestUtils.setField(candidate, "comparisonTargetFactType", type == SettingValueType.NUMBER ? CharacterFactType.AGE : CharacterFactType.PROFILE);
        ReflectionTestUtils.setField(candidate, "comparisonTargetFactKey", key);
        ReflectionTestUtils.setField(candidate, "comparisonBaseSnapshotVersion", 0L);
        ReflectionTestUtils.setField(candidate, "comparisonReason", "소속이 바뀐 현재 장면인지 과거의 소속을 이야기한 것인지 확인해 주세요.");
        entities.persist(candidate);
        return candidate;
    }

    private WorldSettingCandidate worldCandidate(Work work, Episode episode, AnalysisJob job, WorldSetting target,
            String name, String scope, String key, String value, WorldSettingSuggestedOperation operation,
            WorldSettingComparisonReviewReason reason, String matchedScope, String matchedKey, String before,
            WorldSettingConsolidationStatus consolidation) {
        WorldSettingCandidate candidate = WorldSettingCandidate.create(work, episode, job, WorldSettingCategory.LOCATION,
                name, scope, key, value, JSON.arrayNode().add(JSON.objectNode().put("paragraph_index", 1).put("quote", value)),
                BigDecimal.ONE, JSON.objectNode());
        candidate.resolveSubject(target == null ? WorldSettingSubjectResolutionType.NEW : WorldSettingSubjectResolutionType.EXISTING,
                target == null ? "new:location:" + name : "world:" + target.getId(), name,
                target == null ? JSON.arrayNode() : JSON.arrayNode().add(target.getId().toString()));
        candidate.startComparison();
        candidate.completeComparison(target, consolidation, operation, matchedScope, matchedKey, reason, scope, key, before, value,
                "추출한 설정과 기존 설정을 확인해 주세요.", JSON.objectNode(), LocalDateTime.now());
        entities.persist(candidate);
        return candidate;
    }
}
