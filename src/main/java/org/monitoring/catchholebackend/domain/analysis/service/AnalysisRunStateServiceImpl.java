package org.monitoring.catchholebackend.domain.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.event.AnalysisRunInvalidatedEvent;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisAutomaticInputFormat;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisLiveStateReader;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateSlices;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisRunStateServiceImpl implements AnalysisRunStateService {

    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisStateJournal journal;
    private final List<AnalysisStateSource> stateSources;
    private final ApplicationEventPublisher eventPublisher;
    private final AnalysisPendingReferenceSource pendingReferenceSource;
    private final AnalysisJobSourceRepository jobSourceRepository;
    private final org.monitoring.catchholebackend.domain.validation.processor.ValidationSettingsLock settingsLock;
    private final AnalysisLiveStateReader liveState;

    /*
     * #387 자동 누적 claim 입력을 format 2(지문 + references)로 저장하고 캐릭터·세계관은 실행 중 대상별로 읽는다. 기본 true다.
     * 끄면 새 claim만 format 1이 된다. 켜기 전에 claim한 Job(format 1)과 그 재시도는 저장된 사본을 그대로 읽는다. 코드를 이 단계 전으로
     * 되돌리려면 PENDING·RUNNING·재개 대기인 format 2 Job이 없어야 한다(ADR-0005).
     */
    @org.springframework.beans.factory.annotation.Value("${analysis.automatic-input.live-settings-enabled:true}")
    private boolean liveSettingsEnabled;

    private static final com.fasterxml.jackson.databind.ObjectMapper STORED_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    @Transactional
    public void initializeRun(List<AnalysisJob> jobs) {
        if (jobs.isEmpty()) {
            throw conflict();
        }
        AnalysisJob first = jobs.getFirst();
        if (first.getEpisode() == null && !first.isSettingBookSource() || first.isSettingBookSource() && jobs.size() != 1) {
            throw conflict();
        }
        // 현재 slot에서 사라진 REMOVE와 인물 발견도 미래 시점의 확정 이력이다.
        if (!first.isSettingBookSource()) assertNoFutureConfirmedHistory(first.getWork().getId(), first.getEpisode().getEpisodeNo());
        // #387 자동 누적은 run_base_state를 읽지 않고 회차마다 claim 때 입력을 고정한다. 생성 때 전체 설정을 복사하지 않는다.
        boolean automatic = first.isAutomaticReview();
        ObjectNode base = automatic ? automaticRunBaseMarker() : journal.emptyState();
        Set<String> domains = new HashSet<>();
        for (AnalysisStateSource source : stateSources) {
            if (!List.of("characters", "worldSettings", "references").contains(source.domain())
                    || !domains.add(source.domain())) {
                throw conflict();
            }
            if (automatic) continue;
            JsonNode captured = source.capture(first.getWork());
            if (captured == null || !captured.isObject()) {
                throw conflict();
            }
            // 조립 중에는 읽기만 한다. initializeOrderedRun이 저장 경계에서 복사한다.
            base.set(source.domain(), captured);
        }
        if (!domains.containsAll(List.of("characters", "worldSettings"))) {
            // 한 도메인의 확정 설정을 빈 상태로 오인해 실행을 시작하지 않는다.
            throw conflict();
        }
        if (!first.isSettingBookSource()) {
            if (automatic) {
                assertNoFutureFrozenSource(first.getWork().getId(), first.getEpisode().getEpisodeNo());
            } else {
                assertNoFutureSource(base.path("characters"), first.getEpisode().getEpisodeNo());
                assertNoFutureSource(base.path("worldSettings"), first.getEpisode().getEpisodeNo());
            }
        }
        UUID runId = UUID.randomUUID();
        UUID predecessor = null;
        int previousEpisodeNo = -1;
        for (int index = 0; index < jobs.size(); index++) {
            AnalysisJob job = jobs.get(index);
            if (!Objects.equals(job.getWork().getId(), first.getWork().getId())
                    || job.getSourceKind() != first.getSourceKind()
                    || job.getReviewMode() != first.getReviewMode()
                    || !job.isSettingBookSource() && (job.getEpisode() == null || job.getEpisode().getEpisodeNo() <= previousEpisodeNo)) {
                throw conflict();
            }
            job.initializeOrderedRun(runId, 1, index, predecessor, index == 0 ? base : null);
            analysisJobRepository.save(job);
            predecessor = job.getId();
            if (!job.isSettingBookSource()) previousEpisodeNo = job.getEpisode().getEpisodeNo();
        }
    }

    @Override
    public JsonNode getInputState(AnalysisJob job) {
        requireOrdered(job);
        if (job.isAutomaticReview()) {
            if (liveInput(job)) return liveWholeInput(liveView(job));
            return automaticInputForRead(job).deepCopy();
        }
        List<AnalysisJob> chain = analysisJobRepository
                .findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(
                        job.getAnalysisRunId(), job.getRunGeneration());
        if (chain.isEmpty() || chain.getFirst().getRunBaseState() == null) {
            throw conflict();
        }
        JsonNode base = chain.getFirst().getRunBaseState();
        List<AnalysisStateChange> changes = new ArrayList<>();
        UUID predecessor = null;
        int sequence = 0;
        int previousEpisodeNo = -1;
        for (AnalysisJob member : chain) {
            if (!Objects.equals(member.getWork().getId(), job.getWork().getId())
                    || !Objects.equals(member.getRunSequence(), sequence)
                    || !Objects.equals(member.getPredecessorJobId(), predecessor)
                    || !member.hasCurrentSourceVersion() || member.getEpisode().getEpisodeNo() <= previousEpisodeNo) {
                throw conflict();
            }
            if (Objects.equals(member.getId(), job.getId())) {
                return apply(base, changes);
            }
            if (sequence >= job.getRunSequence()) {
                throw conflict();
            }
            if (member.getStatus() != AnalysisJobStatus.SUCCEEDED
                    || member.getJournalStatus() != AnalysisJournalStatus.SEALED) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_PREDECESSOR_INCOMPLETE);
            }
            JsonNode record = requireEnvelope(member);
            String beforeHash = journal.hash(apply(base, changes));
            if (!beforeHash.equals(member.getInputStateHash())) {
                throw conflict();
            }
            changes.addAll(readChanges(record));
            if (!journal.hash(apply(base, changes)).equals(record.path("outputStateHash").asText())) {
                throw conflict();
            }
            predecessor = member.getId();
            previousEpisodeNo = member.getEpisode().getEpisodeNo();
            sequence++;
        }
        throw conflict();
    }

    @Override
    public JsonNode getProjectedState(AnalysisJob job) {
        if (liveInput(job)) {
            LiveView view = liveView(job);
            return apply(liveWholeInput(view), currentChanges(job));
        }
        JsonNode input = projectableInput(job);
        // 수동 입력은 이미 재생한 독립 값이다. 빌린 자동 입력만 반환 경계에서 복사한다.
        if (job.getStateJournal() == null) return job.isAutomaticReview() ? input.deepCopy() : input;
        return apply(input, readChanges(requireEnvelope(job)));
    }

    @Override
    public JsonNode getInputTarget(AnalysisJob job, String domain, String ref) {
        if (liveInput(job)) return AnalysisStateSlices.target(liveView(job).start(Map.of(domain, Set.of(ref)), false), domain, ref).deepCopy();
        return AnalysisStateSlices.target(inputStateForValidation(job), domain, ref).deepCopy();
    }

    @Override
    public JsonNode getProjectedTarget(AnalysisJob job, String domain, String ref) {
        if (liveInput(job)) return AnalysisStateSlices.target(liveView(job).project(currentChanges(job), Map.of(domain, Set.of(ref)), false), domain, ref);
        return AnalysisStateSlices.target(projectedForRead(job), domain, ref).deepCopy();
    }

    @Override
    public JsonNode getProjectedReferences(AnalysisJob job) {
        if (liveInput(job)) return AnalysisStateSlices.references(liveView(job).project(currentChanges(job), Map.of(), true));
        return AnalysisStateSlices.references(projectedForRead(job)).deepCopy();
    }

    @Override
    public ObjectNode getProjectedSubset(AnalysisJob job, String domain, java.util.Collection<String> refs) {
        if (liveInput(job)) return AnalysisStateSlices.subset(liveView(job).project(currentChanges(job),
                Map.of(domain, new LinkedHashSet<>(refs)), true), domain, refs).deepCopy();
        return AnalysisStateSlices.subset(projectedForRead(job), domain, refs).deepCopy();
    }

    @Override
    public List<JsonNode> getProjectedWorldSubjects(AnalysisJob job, String category) {
        if (liveInput(job)) {
            LiveView view = liveView(job);
            List<AnalysisStateChange> changes = currentChanges(job);
            Set<String> refs = new LinkedHashSet<>(liveState.worldSubjectRefs(job.getWork().getId(), category));
            refs.addAll(touched(changes).getOrDefault(AnalysisStateSlices.WORLD_SETTINGS, Set.of()));
            return AnalysisStateSlices.worldSubjects(view.project(changes, Map.of(AnalysisStateSlices.WORLD_SETTINGS, refs), false), category)
                    .stream().<JsonNode>map(JsonNode::deepCopy).toList();
        }
        return AnalysisStateSlices.worldSubjects(projectedForRead(job), category).stream()
                .<JsonNode>map(JsonNode::deepCopy).toList();
    }

    @Override
    public ObjectNode getInputCharacterIdentities(AnalysisJob job) {
        if (liveInput(job)) {
            // 같은 완료 트랜잭션에서 seal한 뒤에는 자동 반영이 확정 설정을 바꿨을 수 있다. seal 때 고정한 반영 전 색인을 쓴다.
            JsonNode pinned = job.getSealedLiveInputIdentities();
            if (pinned != null) return (ObjectNode) pinned;
            assertLiveFingerprint(job);
            return (ObjectNode) asStored(readLive(job, () -> liveState.characterIdentities(job.getWork().getId())));
        }
        return AnalysisStateSlices.characterIdentities(inputStateForValidation(job)).deepCopy();
    }

    @Override
    // claim이 근거 오류를 받아 같은 트랜잭션에서 Job을 claim 입력 실패로 마감하므로, prepareInput처럼 이 예외로 claim 트랜잭션을 되돌리지 않는다.
    @Transactional(readOnly = true, noRollbackFor = org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class)
    public JsonNode getInputCurrentSubjects(AnalysisJob job) {
        if (liveInput(job)) {
            // claim 응답은 이력·근거 사본 없이 현재값만 읽는다. references는 저장 입력의 고정본이다.
            JsonNode stored = assertLiveFingerprint(job);
            ObjectNode current = (ObjectNode) asStored(readLive(job, () -> liveState.currentSubjects(job.getWork())));
            current.set(AnalysisStateSlices.REFERENCES, stored.path(AnalysisStateSlices.REFERENCES).deepCopy());
            return current;
        }
        return getInputState(job);
    }

    /** getProjectedState와 같은 입력 hash·journal 상태 검증을 통과한 입력. 자동 입력은 빌린 노드다. */
    private JsonNode projectableInput(AnalysisJob job) {
        JsonNode input = inputStateForValidation(job);
        if (job.getInputStateHash() == null || !journal.hash(input).equals(job.getInputStateHash())
                || job.getJournalStatus() == AnalysisJournalStatus.INVALIDATED
                || job.getJournalStatus() == AnalysisJournalStatus.INCOMPLETE) {
            throw conflict();
        }
        return input;
    }

    /** getProjectedState와 같은 값이지만 변경 기록이 없으면 빌린 입력을 그대로 준다. 대상별 읽기가 반환 경계에서 복사한다. */
    private JsonNode projectedForRead(AnalysisJob job) {
        JsonNode input = projectableInput(job);
        JsonNode record = job.getStateJournal();
        return record == null ? input : apply(input, readChanges(requireEnvelope(job, record)));
    }

    @Override
    public PreparedState prepareState(AnalysisJob job) {
        if (liveInput(job)) return new PreparedLiveStateImpl(job, liveView(job));
        JsonNode input = getInputState(job);
        if (job.getInputStateHash() == null || !journal.hash(input).equals(job.getInputStateHash())
                || job.getJournalStatus() == AnalysisJournalStatus.INVALIDATED
                || job.getJournalStatus() == AnalysisJournalStatus.INCOMPLETE) {
            throw conflict();
        }
        return new PreparedStateImpl(job, input);
    }

    /** singleton/ThreadLocal 캐시가 아니다. 호출자가 Job 잠금 아래 지역 변수로 소유한다. */
    private final class PreparedStateImpl implements PreparedState {
        private final AnalysisJob job;
        private final JsonNode input;
        private final JsonNode automaticInput;
        private final String inputHash;
        private final Map<String, AnalysisStateChange> applied = new LinkedHashMap<>();
        private JsonNode record;
        private long recordRevision;
        private JsonNode projected;

        private PreparedStateImpl(AnalysisJob job, JsonNode input) {
            this.job = job;
            this.input = input;
            this.automaticInput = job.getAutomaticInputState();
            this.inputHash = job.getInputStateHash();
            reload();
        }

        private void reload() {
            JsonNode nextRecord = job.getStateJournal();
            List<AnalysisStateChange> changes = nextRecord == null ? List.of() : readChanges(requireEnvelope(job, nextRecord));
            JsonNode nextProjected = apply(input, changes);
            Map<String, AnalysisStateChange> nextApplied = new LinkedHashMap<>();
            changes.forEach(change -> nextApplied.put(change.eventId(), change));
            record = nextRecord;
            recordRevision = job.getStateJournalRevision();
            projected = nextProjected;
            applied.clear();
            applied.putAll(nextApplied);
        }

        private void refresh() {
            if (!Objects.equals(inputHash, job.getInputStateHash()) || !job.hasCurrentSourceVersion()
                    || automaticInput != job.getAutomaticInputState()
                    || job.getJournalStatus() == AnalysisJournalStatus.INVALIDATED
                    || job.getJournalStatus() == AnalysisJournalStatus.INCOMPLETE) {
                throw conflict();
            }
            // 다른 도메인 helper가 같은 요청에서 변경을 추가했어도 이전 projection을 반환하지 않는다.
            if (recordRevision != job.getStateJournalRevision()) reload();
        }

        @Override
        public JsonNode projectedState() {
            refresh();
            return projected;
        }

        @Override
        public void append(List<AnalysisStateChange> changes) {
            requireWritable(job);
            refresh();
            if (job.getJournalStatus() != AnalysisJournalStatus.PENDING || !job.hasCurrentSourceVersion()) {
                throw conflict();
            }
            requireEnvelope(job, record);
            Map<String, AnalysisStateChange> added = new LinkedHashMap<>();
            for (AnalysisStateChange change : changes) {
                AnalysisStateChange old = applied.get(change.eventId());
                if (old == null) old = added.putIfAbsent(change.eventId(), change);
                if (old != null && !old.equals(change)) throw conflict();
            }
            if (added.isEmpty()) return;
            List<AnalysisStateChange> delta = List.copyOf(added.values());
            // 새 변경만 검증한다. 실패하면 기존 projection과 영속 journal 모두 그대로 남는다.
            JsonNode next = apply(projected, delta);
            ObjectNode updated = record.deepCopy();
            ((com.fasterxml.jackson.databind.node.ArrayNode) updated.path("changes")).addAll(journal.toJson(delta));
            upgradeStorageVersion(updated, delta);
            job.replacePendingJournal(updated);
            record = updated;
            recordRevision = job.getStateJournalRevision();
            projected = next;
            applied.putAll(added);
        }
    }

    @Override
    @Transactional(noRollbackFor = org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class)
    public boolean prepareInput(AnalysisJob job) {
        if (!job.isOrderedProvisional()) {
            if (job.isSettingBookSource() && !job.hasCurrentSourceVersion()) {
                invalidateMember(job, "설정집 원문 버전이 변경되어 비교를 다시 준비해야 합니다.");
                return false;
            }
            return true;
        }
        // 예상한 stale은 트랜잭션 경계 밖으로 던지지 않아 무효화가 rollback되지 않게 한다.
        try {
            prepareOrderedInput(job);
            return true;
        } catch (AppException exception) {
            if (exception.getResultCode() == AnalysisJobErrorCode.ANALYSIS_RUN_PREDECESSOR_INCOMPLETE) {
                return false;
            }
            if (exception.getResultCode() != AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT
                    && exception.getResultCode() != AnalysisJobErrorCode.ANALYSIS_FUTURE_HISTORY_CONFLICT
                    && exception.getResultCode() != AnalysisJobErrorCode.ANALYSIS_RUN_MODE_INVALID) {
                throw exception;
            }
            invalidateFrom(job, "선행 기록 또는 원문 버전이 변경되어 재분석이 필요합니다.");
            return false;
        }
    }

    private void prepareOrderedInput(AnalysisJob job) {
        if (job.getJournalStatus() != AnalysisJournalStatus.PENDING || !job.hasCurrentSourceVersion()) {
            throw conflict();
        }
        if (job.isAutomaticReview() && job.getAutomaticInputState() == null && liveSettingsEnabled) {
            captureLiveInput(job);
        } else if (job.isAutomaticReview() && job.getAutomaticInputState() == null) {
            automaticPredecessors(job);
            if (!job.isSettingBookSource()) assertNoFutureConfirmedHistory(job.getWork().getId(), job.getSourceEpisodeNo());
            ObjectNode input = journal.emptyState();
            Set<String> domains = new HashSet<>();
            for (AnalysisStateSource source : stateSources) {
                JsonNode captured = source.capture(job.getWork());
                if (!List.of("characters", "worldSettings", "references").contains(source.domain())
                        || !domains.add(source.domain()) || captured == null || !captured.isObject()) throw conflict();
                // 참고 영역만 뒤에서 병합한다. 나머지는 captureAutomaticInput에서 한 번 복사한다.
                input.set(source.domain(), "references".equals(source.domain()) ? captured.deepCopy() : captured);
            }
            if (!domains.containsAll(List.of("characters", "worldSettings"))) throw conflict();
            if (!job.isSettingBookSource()) {
                assertNoFutureSource(input.path("characters"), job.getSourceEpisodeNo());
                assertNoFutureSource(input.path("worldSettings"), job.getSourceEpisodeNo());
            }
            ((ObjectNode) input.path("references")).setAll(pendingReferenceSource.capture(job));
            if (job.isSettingBookSource()) {
                ObjectNode manifest = ((ObjectNode) input.path("references")).putObject("setting-book-input");
                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(manifest, job);
            }
            job.captureAutomaticInput(input);
        }
        boolean live = liveInput(job);
        String hash = journal.hash(live ? automaticInputForRead(job) : inputStateForValidation(job));
        if (job.getInputStateHash() != null && !hash.equals(job.getInputStateHash())) {
            throw conflict();
        }
        // format 2의 재claim은 그 사이 확정 설정이 바뀌지 않았는지 지문으로 확인한다. 바뀌었으면 기존 무효화 경로로 간다.
        if (live && job.getInputStateHash() != null) assertLiveFingerprint(job);
        job.prepareOrderedInput(hash);
        if (job.getStateJournal() == null) {
            job.replacePendingJournal(envelope(job));
        } else if (live) {
            validateLiveChanges(liveView(job), currentChanges(job));
        } else {
            getProjectedState(job);
        }
    }

    /**
     * #387 format 2 claim 입력. 캐릭터·세계관을 복사하지 않고 지문만 저장하며 references(사람의 반려·미확정 참고·설정집 manifest)는
     * format 1과 같은 규칙으로 고정한다. 미래 출처 검사는 생성 때와 같은 scalar 조회다(S0 순회 대신).
     */
    private void captureLiveInput(AnalysisJob job) {
        automaticPredecessors(job);
        if (!job.isSettingBookSource()) {
            assertNoFutureConfirmedHistory(job.getWork().getId(), job.getSourceEpisodeNo());
            assertNoFutureFrozenSource(job.getWork().getId(), job.getSourceEpisodeNo());
        }
        Set<String> domains = new HashSet<>();
        ObjectNode references = null;
        for (AnalysisStateSource source : stateSources) {
            if (!List.of("characters", "worldSettings", "references").contains(source.domain()) || !domains.add(source.domain())) throw conflict();
            if (!"references".equals(source.domain())) continue;
            JsonNode captured = source.capture(job.getWork());
            if (captured == null || !captured.isObject()) throw conflict();
            references = (ObjectNode) captured.deepCopy();
        }
        if (references == null || !domains.containsAll(List.of("characters", "worldSettings"))) throw conflict();
        references.setAll(pendingReferenceSource.capture(job));
        if (job.isSettingBookSource()) {
            org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(references.putObject("setting-book-input"), job);
        }
        job.captureAutomaticInput(AnalysisAutomaticInputFormat.create(liveState.fingerprint(job.getWork().getId()), references));
    }

    @Override
    public void assertValidInput(AnalysisJob job) {
        if (job.isSettingBookSource() && !job.hasCurrentSourceVersion()) throw conflict();
        if (!job.isOrderedProvisional()) return;
        if (job.getJournalStatus() != AnalysisJournalStatus.PENDING || !job.hasCurrentSourceVersion()
                || job.getInputStateHash() == null) {
            throw conflict();
        }
        if (liveInput(job)) {
            assertLiveFingerprint(job);
        } else if (!job.getInputStateHash().equals(journal.hash(inputStateForValidation(job)))) {
            throw conflict();
        }
    }

    /**
     * #387 자동 누적 입력은 claim 때 한 번 쓰고(captureAutomaticInput) 실행 중에는 바뀌지 않는다. 바꾸는 곳은 원문 파기뿐이고
     * 파기는 journal을 INVALIDATED로 바꾸거나 완료된 Job만 다룬다. 그래서 heartbeat·progress·토큰 예약처럼 입력을 읽지 않는
     * 호출마다 100화 작품에서 1MB가 넘는 입력을 다시 hash하지 않는다. 입력을 읽는 경로는 각자 hash를 확인하므로 변조된 입력으로
     * 비교·journal 추가·seal을 할 수는 없다.
     */
    @Override
    public void assertValidLeaseInput(AnalysisJob job) {
        if (!job.isOrderedProvisional() || !job.isAutomaticReview()) {
            assertValidInput(job);
            return;
        }
        if (job.getJournalStatus() != AnalysisJournalStatus.PENDING || !job.hasCurrentSourceVersion()
                || job.getInputStateHash() == null) {
            throw conflict();
        }
        automaticInputForRead(job);
    }

    @Override
    public void validateResume(AnalysisJob job) {
        requireOrdered(job);
        if ((job.getJournalStatus() != AnalysisJournalStatus.PENDING
                && job.getJournalStatus() != AnalysisJournalStatus.INCOMPLETE)
                || !job.hasCurrentSourceVersion()) {
            throw conflict();
        }
        if (job.isAutomaticReview() && job.getAutomaticInputState() == null && job.getInputStateHash() == null) {
            automaticPredecessors(job);
            return;
        }
        if (liveInput(job)) {
            if (job.getInputStateHash() == null) {
                if (job.getStateJournal() != null) throw conflict();
                return;
            }
            // 재개 대기 동안에도 GH201이 확정 설정 쓰기를 막으므로 지문이 같아야 한다.
            JsonNode stored = assertLiveFingerprint(job);
            List<AnalysisStateChange> changes = readChanges(requireEnvelope(job));
            validateLiveChanges(new LiveView(job, stored), changes);
            assertChangesCoverCurrentCandidates(job, changes);
            return;
        }
        JsonNode input = inputStateForValidation(job);
        if (job.getInputStateHash() == null) {
            if (job.getStateJournal() != null) {
                throw conflict();
            }
            return;
        }
        if (!job.getInputStateHash().equals(journal.hash(input))) {
            throw conflict();
        }
        List<AnalysisStateChange> changes = readChanges(requireEnvelope(job));
        apply(input, changes);
        assertChangesCoverCurrentCandidates(job, changes);
    }

    private void assertChangesCoverCurrentCandidates(AnalysisJob job, List<AnalysisStateChange> changes) {
        Set<UUID> currentCandidates = new HashSet<>(analysisJobRepository.findCharacterSourceCandidateIds(job.getId()));
        currentCandidates.addAll(analysisJobRepository.findWorldSourceCandidateIds(job.getId()));
        if (changes.stream().flatMap(change -> change.sourceCandidateIds().stream())
                .anyMatch(id -> !currentCandidates.contains(id))) {
            throw conflict();
        }
    }

    @Override
    @Transactional
    public void appendValidatedChanges(AnalysisJob job, String expectedInputStateHash, List<AnalysisStateChange> changes) {
        if (!job.isOrderedProvisional()) {
            return;
        }
        requireWritable(job);
        assertValidInput(job);
        if (!Objects.equals(job.getInputStateHash(), expectedInputStateHash)) {
            throw conflict();
        }
        ObjectNode record = (ObjectNode) requireEnvelope(job).deepCopy();
        Map<String, AnalysisStateChange> current = new LinkedHashMap<>();
        for (AnalysisStateChange change : readChanges(record)) {
            current.put(change.eventId(), change);
        }
        List<AnalysisStateChange> delta = new ArrayList<>();
        for (AnalysisStateChange change : changes) {
            AnalysisStateChange old = current.putIfAbsent(change.eventId(), change);
            if (old != null && !old.equals(change)) {
                throw conflict();
            }
            if (old == null) delta.add(change);
        }
        List<AnalysisStateChange> merged = List.copyOf(current.values());
        if (liveInput(job)) validateLiveAppend(liveView(job), merged, delta);
        else apply(inputStateForValidation(job), merged);
        record.set("changes", journal.toJson(merged));
        upgradeStorageVersion(record, merged);
        job.replacePendingJournal(record);
    }

    @Override
    @Transactional
    public void seal(AnalysisJob job) {
        if (!job.isOrderedProvisional()) {
            return;
        }
        requireWritable(job);
        assertValidInput(job);
        ObjectNode record = (ObjectNode) requireEnvelope(job).deepCopy();
        Set<UUID> expected = new HashSet<>(analysisJobRepository.findCharacterSourceCandidateIds(job.getId()));
        expected.addAll(analysisJobRepository.findWorldSourceCandidateIds(job.getId()));
        Set<UUID> covered = new HashSet<>();
        readChanges(record).forEach(change -> covered.addAll(change.sourceCandidateIds()));
        if (!covered.equals(expected)) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_CHECKPOINT_INCOMPLETE,
                    "현재 회차의 모든 후보 판단이 불변 변경 기록에 보존되지 않았습니다.");
        }
        if (liveInput(job)) {
            sealLive(job, record);
            return;
        }
        record.put("outputStateHash", journal.hash(getProjectedState(job)));
        job.replacePendingJournal(record);
        job.sealJournal();
    }

    /**
     * #387 format 2의 seal. 자동 누적 journal은 저장된 시작 상태에서 replay되지 않으므로(수동 누적만 replay한다)
     * outputStateHash는 {inputStateHash, changes}의 hash다. 같은 완료 트랜잭션의 자동 반영이 확정 설정을 바꾸기 전에
     * 반영 전 인물 식별 색인을 Job에 고정한다(설정집 동일 인물 검증이 반영 중에 읽는다).
     */
    private void sealLive(AnalysisJob job, ObjectNode record) {
        LiveView view = liveView(job);
        validateLiveChanges(view, readChanges(record));
        ObjectNode output = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        output.put("inputStateHash", job.getInputStateHash());
        output.set("changes", record.path("changes").deepCopy());
        record.put("outputStateHash", journal.hash(output));
        JsonNode identities = asStored(readLive(job, () -> liveState.characterIdentities(job.getWork().getId())));
        job.replacePendingJournal(record);
        job.sealJournal();
        job.pinSealedLiveInputIdentities(identities);
    }

    @Override
    @Transactional
    public void invalidateFrom(AnalysisJob job, String reason) {
        if (!job.isOrderedProvisional()) {
            return;
        }
        requireInvalidationReason(reason);
        analysisJobRepository.findRunTailForUpdate(job.getAnalysisRunId(), job.getRunGeneration(), job.getRunSequence())
                .forEach(member -> invalidateMember(member, reason));
    }

    @Override
    public void confirmedSettingsChanged(UUID workId) {
        eventPublisher.publishEvent(new org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents.ConfirmedSettingsChanged(workId));
    }

    @Override
    public void assertSettingMutationAllowed(UUID workId) {
        if (analysisJobRepository.existsUnfinishedOrderedAnalysis(workId)) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_REVIEW_WAIT_REQUIRED);
        }
    }

    @Override
    public void assertConfirmedSettingMutationAllowed(UUID workId) {
        assertSettingMutationAllowed(workId);
        settingsLock.assertUnlocked(workId);
    }

    @Override
    public void assertReportCorrectionAllowed(UUID workId, UUID reportId) {
        assertSettingMutationAllowed(workId);
        settingsLock.assertUnlockedForCorrection(workId, reportId);
    }

    @Override
    @Transactional
    public void invalidateRunsForEpisodeChangeForUpdate(UUID workId, UUID episodeId, int firstAffectedEpisodeNo, String reason) {
        requireInvalidationReason(reason);
        eventPublisher.publishEvent(new org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents.EpisodeSourceChanged(workId, episodeId));
        analysisJobRepository.findAffectedOrderedJobsForUpdate(workId, firstAffectedEpisodeNo).stream()
                .filter(job -> !preservesCompletedSuccessor(job, episodeId))
                .forEach(member -> invalidateMember(member, reason));
    }

    @Override
    @Transactional
    public void invalidateRunsForSettingBookChangeForUpdate(UUID workId, UUID bookId, String reason) {
        requireInvalidationReason(reason);
        analysisJobRepository.findAllByWorkIdAndSourceSettingBookIdOrderByCreatedAtDescIdDesc(workId, bookId)
                .stream().filter(job -> !(job.isOrderedProvisional() ? job.isCompletedOrderedAnalysis()
                        : job.getStatus() == AnalysisJobStatus.SUCCEEDED)).forEach(job -> invalidateMember(job, reason));
    }

    private boolean preservesCompletedSuccessor(AnalysisJob job, UUID changedEpisodeId) {
        return job.getEpisode() != null && !job.getEpisode().getId().equals(changedEpisodeId)
                && job.isCompletedOrderedAnalysis();
    }

    private void requireInvalidationReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new IllegalArgumentException("무효화 사유는 1~200자로 작성해야 합니다.");
        }
    }

    @Override
    @Transactional
    public void purgeSourceEvidenceForWorkForUpdate(UUID workId, UUID episodeId, int sourceEpisodeNo) {
        purgeSourceEvidenceForWorkForUpdate(workId, episodeId, sourceEpisodeNo, null);
    }

    @Override @Transactional
    public void purgeSourceEvidenceForWorkForUpdate(UUID workId, UUID episodeId, int sourceEpisodeNo, String retainedContentKey) {
        eventPublisher.publishEvent(new org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents.EpisodeSourceChanged(workId, episodeId, retainedContentKey));
        analysisJobRepository.findOrderedJobsForSourcePurgeForUpdate(workId, sourceEpisodeNo).forEach(job -> {
            if (!preservesCompletedSuccessor(job, episodeId)) {
                invalidateMember(job, "원문 근거가 파기되어 기존 누적 입력을 재사용할 수 없습니다.");
            }
            job.purgeJournalSourceEvidence(journal.purgeSourceEvidence(job.getStateJournal()));
        });
    }

    private void invalidateMember(AnalysisJob job, String reason) {
        boolean running = job.getStatus() == AnalysisJobStatus.RUNNING;
        boolean canceled = running || job.getStatus() == AnalysisJobStatus.PENDING;
        if (job.isOrderedProvisional()) job.invalidateJournal(reason);
        else if (job.isSettingBookSource()) job.cancelForSourceChange(reason);
        if (running && job.getEpisode() != null && job.getEpisode().getStatus() != EpisodeStatus.ARCHIVED) {
            job.getEpisode().markFailed();
        }
        if (canceled) {
            // 동기 이벤트로 원자성을 유지하며 token → lease → state service의 빈 순환을 피한다.
            eventPublisher.publishEvent(new AnalysisRunInvalidatedEvent(job.getId()));
        }
    }

    // ---- #387 format 2: 캐릭터·세계관은 실행 중 지금 DB에서 대상별로 읽고 이 Job의 변경만 그 대상에 적용한다 ----

    private boolean liveInput(AnalysisJob job) {
        return AnalysisAutomaticInputFormat.isLive(job);
    }

    /**
     * 저장 입력의 hash와 지금 지문을 확인한다. 실행 중에는 GH201이 확정 설정 쓰기를 막으므로 지문이 claim 때와 같아야 하며,
     * 다르면 그 보호를 우회한 쓰기(legacy 단건 비교 EXCLUDE 등)가 있었던 것이라 기존 무효화 경로로 간다.
     */
    private JsonNode assertLiveFingerprint(AnalysisJob job) {
        JsonNode stored = automaticInputForRead(job);
        if (job.getInputStateHash() == null || !journal.hash(stored).equals(job.getInputStateHash())
                || !stored.path("fingerprint").isTextual()) {
            throw conflict();
        }
        if (!liveState.fingerprint(job.getWork().getId()).equals(stored.path("fingerprint").asText())) {
            // 읽기를 부른 트랜잭션이 되돌려지면 새 트랜잭션에서 이 실행을 무효화한다(AnalysisJobWorkerServiceImpl).
            eventPublisher.publishEvent(new org.monitoring.catchholebackend.domain.analysis.event.AnalysisLiveInputChangedEvent(job.getId()));
            throw conflict();
        }
        return stored;
    }

    @Override
    public boolean isLiveInputStale(AnalysisJob job) {
        if (!liveInput(job) || job.getInputStateHash() == null) return false;
        JsonNode stored = job.getAutomaticInputState();
        return journal.hash(stored).equals(job.getInputStateHash()) && stored.path("fingerprint").isTextual()
                && !liveState.fingerprint(job.getWork().getId()).equals(stored.path("fingerprint").asText());
    }

    /**
     * 대상을 읽는 format 2 경로의 공통 확인. format 1의 투영 읽기처럼 무효·미완료 journal은 거절한다. seal 뒤에도 같은 완료
     * 트랜잭션의 자동 반영 전에는 지금 DB가 claim 때 값과 같다. 반영이 대상 값을 바꾼 뒤에는 지문이 달라져 거절되므로
     * 이 Job의 변경을 두 번 적용한 값을 돌려주지 않는다(반영 중 인물 식별 색인은 seal이 고정한 값을 쓴다).
     */
    private LiveView liveView(AnalysisJob job) {
        JsonNode stored = assertLiveFingerprint(job);
        if (job.getJournalStatus() == AnalysisJournalStatus.INVALIDATED
                || job.getJournalStatus() == AnalysisJournalStatus.INCOMPLETE) throw conflict();
        return new LiveView(job, stored);
    }

    private List<AnalysisStateChange> currentChanges(AnalysisJob job) {
        JsonNode record = job.getStateJournal();
        return record == null ? List.of() : readChanges(requireEnvelope(job, record));
    }

    /** 변경이 닿은 대상(도메인 → ref). references는 담지 않는다. */
    private static Map<String, Set<String>> touched(List<AnalysisStateChange> changes) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (AnalysisStateChange change : changes) {
            if (AnalysisStateSlices.REFERENCES.equals(change.path().getFirst())) continue;
            result.computeIfAbsent(change.path().getFirst(), ignored -> new LinkedHashSet<>()).add(change.path().get(1));
        }
        return result;
    }

    /** 변경 전체를 그 변경이 닿은 대상만 담은 시작 상태에 적용해 본다. format 1의 전체 입력 적용과 같은 경로·식별자 검증이다(seal·재개·재claim). */
    private void validateLiveChanges(LiveView view, List<AnalysisStateChange> changes) {
        view.project(changes, touched(changes), true);
    }

    /**
     * 새 변경(delta)이 닿은 대상만 그 대상의 기존 변경과 함께 적용해 본다. 다른 대상의 변경은 delta와 독립이고 추가할 때 이미
     * 검증했으며, event 식별자 충돌은 호출자가 기존 기록 전체와 먼저 비교한다. 추가마다 기록이 닿은 모든 대상을 다시 읽지 않기 위함이다.
     */
    private void validateLiveAppend(LiveView view, List<AnalysisStateChange> merged, List<AnalysisStateChange> delta) {
        boolean references = delta.stream().anyMatch(change -> AnalysisStateSlices.REFERENCES.equals(change.path().getFirst()));
        view.project(merged, touched(delta), references);
    }

    /** 저장 입력 전체(format 1 claim 사본과 같은 모양). 검증·테스트용이며 분석 도메인의 읽기는 쓰지 않는다. */
    private ObjectNode liveWholeInput(LiveView view) {
        ObjectNode state = journal.emptyState();
        for (AnalysisStateSource source : stateSources) {
            if (AnalysisStateSlices.REFERENCES.equals(source.domain())) continue;
            state.set(source.domain(), asStored(readLive(view.job, () -> source.capture(view.job.getWork()))));
        }
        state.set(AnalysisStateSlices.REFERENCES, view.stored.path(AnalysisStateSlices.REFERENCES).deepCopy());
        return state;
    }

    /**
     * 실행 중 대상을 처음 읽다가 확정 근거 연결 오류가 나면 그 lease의 Job을 claim 입력 실패와 같은 모양으로 마감하도록 알린다.
     * 이 읽기를 부른 트랜잭션은 예외로 되돌려지고, 마감은 되돌린 뒤의 새 트랜잭션이 한다(AnalysisJobWorkerServiceImpl).
     */
    private <T> T readLive(AnalysisJob job, java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException exception) {
            eventPublisher.publishEvent(new org.monitoring.catchholebackend.domain.analysis.event.AnalysisLiveInputEvidenceFailedEvent(
                    job.getId(), job.getLeaseToken(), exception.getMessage(), exception.getErrorContext().get("failureDiagnostics")));
            throw exception;
        }
    }

    /** live 값을 DB(jsonb)에서 읽은 값과 같은 노드 모양으로 맞춘다(숫자 노드 종류 등). format 1의 저장 사본 읽기와 같게 하기 위함이다. */
    private static JsonNode asStored(JsonNode node) {
        try {
            return STORED_JSON.readTree(STORED_JSON.writeValueAsBytes(node));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("자동 누적 live 입력을 직렬화할 수 없습니다.", exception);
        }
    }

    /** 한 번의 처리 안에서 읽은 대상의 지금 값(DB 모양)을 담는다. 요청 사이에 공유하지 않는다. */
    private final class LiveView {
        private final AnalysisJob job;
        private final JsonNode stored;
        private final Map<String, Map<String, JsonNode>> subjects = new HashMap<>();

        private LiveView(AnalysisJob job, JsonNode stored) {
            this.job = job;
            this.stored = stored;
        }

        private void load(String domain, java.util.Collection<String> refs) {
            Map<String, JsonNode> loaded = subjects.computeIfAbsent(domain, ignored -> new HashMap<>());
            List<String> missing = refs.stream().filter(ref -> !loaded.containsKey(ref)).distinct().toList();
            if (missing.isEmpty()) return;
            ObjectNode read = readLive(job, () -> liveState.subjects(job.getWork(), domain, missing));
            for (String ref : missing) {
                loaded.put(ref, read.has(ref) ? asStored(read.get(ref)) : com.fasterxml.jackson.databind.node.MissingNode.getInstance());
            }
        }

        /** refsByDomain 대상의 claim 입력(이 Job의 변경 전). 없는 대상은 담지 않는다. withReferences면 저장된 references도 담는다. */
        private ObjectNode start(Map<String, ? extends java.util.Collection<String>> refsByDomain, boolean withReferences) {
            ObjectNode state = journal.emptyState();
            refsByDomain.forEach((domain, refs) -> {
                load(domain, refs);
                if (!(state.path(domain) instanceof ObjectNode targets)) throw new IllegalArgumentException("누적 상태의 대상 영역은 characters 또는 worldSettings여야 합니다.");
                for (String ref : refs) {
                    JsonNode subject = subjects.get(domain).get(ref);
                    if (!subject.isMissingNode()) targets.set(ref, subject.deepCopy());
                }
            });
            if (withReferences) state.set(AnalysisStateSlices.REFERENCES, stored.path(AnalysisStateSlices.REFERENCES).deepCopy());
            return state;
        }

        /** 이 Job의 변경 중 그 대상(withReferences면 references 포함)에 닿는 것만 원래 순서로 적용한 상태. */
        private JsonNode project(List<AnalysisStateChange> changes, Map<String, ? extends java.util.Collection<String>> refsByDomain, boolean withReferences) {
            List<AnalysisStateChange> relevant = changes.stream().filter(change -> {
                String domain = change.path().getFirst();
                if (AnalysisStateSlices.REFERENCES.equals(domain)) return withReferences;
                java.util.Collection<String> refs = refsByDomain.get(domain);
                return refs != null && refs.contains(change.path().get(1));
            }).toList();
            return apply(start(refsByDomain, withReferences), relevant);
        }
    }

    /** format 2의 PreparedState. 대상을 요청할 때 한 번 읽어 두고, 변경 기록이 바뀌면(다른 helper의 append) 다시 적용한다. */
    private final class PreparedLiveStateImpl implements PreparedState {
        private final AnalysisJob job;
        private final LiveView view;
        private final JsonNode automaticInput;
        private final String inputHash;
        private final Map<String, AnalysisStateChange> applied = new LinkedHashMap<>();
        private List<AnalysisStateChange> changes = List.of();
        private JsonNode record;
        private long recordRevision;

        private PreparedLiveStateImpl(AnalysisJob job, LiveView view) {
            this.job = job;
            this.view = view;
            this.automaticInput = job.getAutomaticInputState();
            this.inputHash = job.getInputStateHash();
            reload();
        }

        private void reload() {
            JsonNode nextRecord = job.getStateJournal();
            // 기록은 추가할 때마다 닿은 대상에서 검증했고 seal·재개가 전체를 다시 검증한다. 준비마다 기록이 닿은 모든 대상을 읽지 않는다.
            List<AnalysisStateChange> next = nextRecord == null ? List.of() : readChanges(requireEnvelope(job, nextRecord));
            record = nextRecord;
            recordRevision = job.getStateJournalRevision();
            changes = next;
            applied.clear();
            next.forEach(change -> applied.put(change.eventId(), change));
        }

        private void refresh() {
            if (!Objects.equals(inputHash, job.getInputStateHash()) || !job.hasCurrentSourceVersion()
                    || automaticInput != job.getAutomaticInputState()
                    || job.getJournalStatus() == AnalysisJournalStatus.INVALIDATED
                    || job.getJournalStatus() == AnalysisJournalStatus.INCOMPLETE) {
                throw conflict();
            }
            if (recordRevision != job.getStateJournalRevision()) reload();
        }

        @Override
        public JsonNode projectedState() {
            refresh();
            return apply(liveWholeInput(view), changes);
        }

        @Override
        public JsonNode projectedTarget(String domain, String ref) {
            refresh();
            return AnalysisStateSlices.target(view.project(changes, Map.of(domain, Set.of(ref)), false), domain, ref);
        }

        @Override
        public JsonNode projectedReferences() {
            refresh();
            return AnalysisStateSlices.references(view.project(changes, Map.of(), true));
        }

        @Override
        public ObjectNode projectedSubset(String domain, java.util.Collection<String> refs) {
            refresh();
            return AnalysisStateSlices.subset(view.project(changes, Map.of(domain, new LinkedHashSet<>(refs)), true), domain, refs);
        }

        @Override
        public void append(List<AnalysisStateChange> delta) {
            requireWritable(job);
            refresh();
            if (job.getJournalStatus() != AnalysisJournalStatus.PENDING || !job.hasCurrentSourceVersion()) throw conflict();
            requireEnvelope(job, record);
            Map<String, AnalysisStateChange> added = new LinkedHashMap<>();
            for (AnalysisStateChange change : delta) {
                AnalysisStateChange old = applied.get(change.eventId());
                if (old == null) old = added.putIfAbsent(change.eventId(), change);
                if (old != null && !old.equals(change)) throw conflict();
            }
            if (added.isEmpty()) return;
            List<AnalysisStateChange> merged = new ArrayList<>(changes);
            merged.addAll(added.values());
            // 새 변경이 닿은 대상에 그 대상의 기존 변경과 함께 적용해 본다. 실패하면 기존 기록은 그대로다.
            validateLiveAppend(view, merged, List.copyOf(added.values()));
            ObjectNode updated = record.deepCopy();
            ((com.fasterxml.jackson.databind.node.ArrayNode) updated.path("changes")).addAll(journal.toJson(List.copyOf(added.values())));
            upgradeStorageVersion(updated, List.copyOf(added.values()));
            job.replacePendingJournal(updated);
            record = updated;
            recordRevision = job.getStateJournalRevision();
            changes = List.copyOf(merged);
            applied.putAll(added);
        }
    }

    private void requireOrdered(AnalysisJob job) {
        if (!job.isOrderedProvisional() || job.getRunGeneration() == null
                || job.getRunSequence() == null || job.getAnalysisRunId() == null) {
            throw conflict();
        }
    }

    /** 검증·hash 계산 안에서만 빌린다. 반환하거나 수정할 상태는 기존 방어 복사 경계를 따른다. */
    private JsonNode inputStateForValidation(AnalysisJob job) {
        return job.isAutomaticReview() ? automaticInputForRead(job) : getInputState(job);
    }

    private JsonNode automaticInputForRead(AnalysisJob job) {
        requireOrdered(job);
        automaticPredecessors(job);
        JsonNode input = job.getAutomaticInputState();
        if (input == null || !input.isObject()) throw conflict();
        return input;
    }

    private void automaticPredecessors(AnalysisJob job) {
        List<AnalysisJobSourceView> chain = jobSourceRepository.findRunMembers(
                job.getAnalysisRunId(), job.getRunGeneration());
        UUID previousId = null;
        int sequence = 0;
        int previousEpisode = -1;
        for (AnalysisJobSourceView member : chain) {
            if (!member.isAutomaticReview() || !Objects.equals(member.getWorkId(), job.getWork().getId())
                    || !Objects.equals(member.getRunSequence(), sequence)
                    || !Objects.equals(member.getPredecessorJobId(), previousId)
                    || !member.hasCurrentSourceVersion() || member.getSourceKind() != job.getSourceKind()
                    || member.isSettingBookSource() && (chain.size() != 1 || sequence != 0)
                    || !member.isSettingBookSource() && member.getSourceEpisodeNo() <= previousEpisode) throw conflict();
            if (member.getId().equals(job.getId())) return;
            if (sequence >= job.getRunSequence()) throw conflict();
            if (member.getStatus() != AnalysisJobStatus.SUCCEEDED
                    || member.getJournalStatus() != AnalysisJournalStatus.SEALED
                    || member.getAutomaticAppliedAt() == null) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_PREDECESSOR_INCOMPLETE);
            }
            previousId = member.getId();
            if (!member.isSettingBookSource()) previousEpisode = member.getSourceEpisodeNo();
            sequence++;
        }
        throw conflict();
    }

    private void requireWritable(AnalysisJob job) {
        if (job.getStatus() != AnalysisJobStatus.RUNNING || job.getLeaseToken() == null
                || job.isLeaseExpired(LocalDateTime.now())) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_LEASE_CONFLICT);
        }
    }

    private ObjectNode envelope(AnalysisJob job) {
        ObjectNode record = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        record.put("formatVersion", AnalysisStateJournal.LEGACY_STORAGE_VERSION);
        record.put("runId", job.getAnalysisRunId().toString());
        record.put("workId", job.getWork().getId().toString());
        record.put("generation", job.getRunGeneration());
        record.put("sequence", job.getRunSequence());
        record.put("episodeNo", job.getSourceEpisodeNo());
        if (job.isSettingBookSource()) {
            record.put("sourceKind", job.getSourceKind().name());
            record.put("sourceSettingBookId", job.getSourceSettingBook().getId().toString());
            record.put("sourceSettingBookRevisionId", job.getSourceSettingBookRevision().getId().toString());
            record.put("sourceSettingBookRevisionNo", job.getSourceSettingBookRevision().getRevisionNo());
            record.put("sourceReferenceTimeKind", job.getSourceSettingBookRevision().getReferenceTimeKind().name());
            record.put("sourceReferenceEpisodeNo", job.getSourceSettingBookRevision().getReferenceEpisodeNo());
        }
        record.put("jobId", job.getId().toString());
        record.put("inputStateHash", job.getInputStateHash());
        record.putNull("outputStateHash");
        record.putArray("changes");
        return record;
    }

    private JsonNode requireEnvelope(AnalysisJob job) {
        return requireEnvelope(job, job.getStateJournal());
    }

    private JsonNode requireEnvelope(AnalysisJob job, JsonNode record) {
        if (record == null || !record.isObject() || record.path("sourceEvidencePurged").asBoolean(false)
                || !supportedStorageVersion(record.path("formatVersion"))
                || !record.path("runId").asText().equals(job.getAnalysisRunId().toString())
                || !record.path("workId").asText().equals(job.getWork().getId().toString())
                || record.path("generation").asLong(-1) != job.getRunGeneration()
                || record.path("sequence").asInt(-1) != job.getRunSequence()
                || !matchesEnvelopeSource(record, job)
                || !record.path("jobId").asText().equals(job.getId().toString())
                || !record.path("inputStateHash").asText().equals(job.getInputStateHash())) {
            throw conflict();
        }
        return record;
    }

    private boolean supportedStorageVersion(JsonNode version) {
        return version.isIntegralNumber() && version.canConvertToInt() && (version.asInt(-1) == AnalysisStateJournal.LEGACY_STORAGE_VERSION
                || version.asInt(-1) == AnalysisStateJournal.HISTORY_APPEND_STORAGE_VERSION);
    }

    private void upgradeStorageVersion(ObjectNode record, List<AnalysisStateChange> changes) {
        if (changes.stream().anyMatch(AnalysisStateChange::appendsHistory)) {
            // v1 Java는 v2 envelope를 거절한다. 추가 배열을 전체 대체로 오해하게 두지 않는다.
            record.put("formatVersion", AnalysisStateJournal.HISTORY_APPEND_STORAGE_VERSION);
        }
    }

    private boolean matchesEnvelopeSource(JsonNode record, AnalysisJob job) {
        if (!job.isSettingBookSource()) return record.path("episodeNo").isIntegralNumber()
                && record.path("episodeNo").asInt() == job.getSourceEpisodeNo()
                && "EPISODE".equals(record.path("sourceKind").asText("EPISODE"));
        return "SETTING_BOOK".equals(record.path("sourceKind").asText()) && record.path("episodeNo").isNull()
                && job.getSourceSettingBook().getId().toString().equals(record.path("sourceSettingBookId").asText())
                && job.getSourceSettingBookRevision().getId().toString().equals(record.path("sourceSettingBookRevisionId").asText())
                && job.getSourceSettingBookRevision().getRevisionNo() == record.path("sourceSettingBookRevisionNo").asInt(-1)
                && job.getSourceSettingBookRevision().getReferenceTimeKind().name()
                    .equals(record.path("sourceReferenceTimeKind").asText("UNSPECIFIED"))
                && java.util.Objects.equals(job.getSourceSettingBookRevision().getReferenceEpisodeNo(),
                    record.path("sourceReferenceEpisodeNo").isIntegralNumber() ? record.path("sourceReferenceEpisodeNo").asInt() : null);
    }

    private List<AnalysisStateChange> readChanges(JsonNode record) {
        try {
            List<AnalysisStateChange> changes = journal.fromJson(record.path("changes"));
            if (record.path("formatVersion").asInt() == AnalysisStateJournal.LEGACY_STORAGE_VERSION
                    && changes.stream().anyMatch(AnalysisStateChange::appendsHistory)) {
                throw conflict();
            }
            return changes;
        } catch (IllegalArgumentException exception) {
            throw conflict();
        }
    }

    private JsonNode apply(JsonNode base, List<AnalysisStateChange> changes) {
        try {
            return journal.apply(base, changes);
        } catch (IllegalArgumentException exception) {
            throw conflict();
        }
    }

    private void assertNoFutureSource(JsonNode node, int firstEpisodeNo) {
        if (node.isObject()) {
            for (String key : List.of("sourceEpisodeNo", "latestSourceEpisodeNo")) {
                if (node.path(key).isIntegralNumber() && node.path(key).asInt() >= firstEpisodeNo) {
                    throw new AppException(AnalysisJobErrorCode.ANALYSIS_FUTURE_HISTORY_CONFLICT);
                }
            }
        }
        if (node.isContainerNode()) {
            node.forEach(child -> assertNoFutureSource(child, firstEpisodeNo));
        }
    }

    private void assertNoFutureConfirmedHistory(UUID workId, int firstEpisodeNo) {
        Integer[] latestSources = {
                analysisJobRepository.findLatestCharacterFactSourceEpisodeNo(workId),
                analysisJobRepository.findLatestCharacterFirstAppearanceEpisodeNo(workId),
                analysisJobRepository.findLatestConfirmedCharacterSourceEpisodeNo(workId),
                analysisJobRepository.findLatestConfirmedWorldSourceEpisodeNo(workId)
        };
        for (Integer sourceEpisodeNo : latestSources) {
            if (sourceEpisodeNo != null && sourceEpisodeNo >= firstEpisodeNo) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_FUTURE_HISTORY_CONFLICT);
            }
        }
    }

    /**
     * #387 자동 누적 생성의 미래 출처 검사. 수동 S0 순회({@link #assertNoFutureSource})가 보던 값 중 회차 이동 뒤
     * 지금 회차 번호와 다를 수 있는 추출 Job의 고정 회차 번호를 scalar로 확인한다. 지금 회차 번호는
     * {@link #assertNoFutureConfirmedHistory}가 이미 본다.
     */
    private void assertNoFutureFrozenSource(UUID workId, int firstEpisodeNo) {
        Integer[] latestSources = {
                analysisJobRepository.findLatestCurrentCharacterSourceJobEpisodeNo(workId),
                analysisJobRepository.findLatestConfirmedDiscoveryJobEpisodeNo(workId),
                analysisJobRepository.findLatestConfirmedWorldSourceJobEpisodeNo(workId)
        };
        for (Integer sourceEpisodeNo : latestSources) {
            if (sourceEpisodeNo != null && sourceEpisodeNo >= firstEpisodeNo) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_FUTURE_HISTORY_CONFLICT);
            }
        }
    }

    /**
     * #387 자동 누적 run의 seq 0 run_base_state. V77 CHECK가 seq 0에 object를 요구해 저장하며, 아무도 읽지 않는다.
     * characters·worldSettings·references key가 없어 잘못 replay하면 변경 경로 검증에서 거절된다.
     */
    private static ObjectNode automaticRunBaseMarker() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("automaticInputCapturedAtClaim", true);
    }

    private AppException conflict() {
        return new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
    }
}
