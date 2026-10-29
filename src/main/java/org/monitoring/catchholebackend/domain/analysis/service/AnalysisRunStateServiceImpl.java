package org.monitoring.catchholebackend.domain.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.event.AnalysisRunInvalidatedEvent;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
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
        ObjectNode base = journal.emptyState();
        Set<String> domains = new HashSet<>();
        for (AnalysisStateSource source : stateSources) {
            if (!List.of("characters", "worldSettings", "references").contains(source.domain())
                    || !domains.add(source.domain())) {
                throw conflict();
            }
            JsonNode captured = source.capture(first.getWork());
            if (captured == null || !captured.isObject()) {
                throw conflict();
            }
            base.set(source.domain(), captured.deepCopy());
        }
        if (!domains.containsAll(List.of("characters", "worldSettings"))) {
            // 한 도메인의 확정 설정을 빈 상태로 오인해 실행을 시작하지 않는다.
            throw conflict();
        }
        if (!first.isSettingBookSource()) {
            assertNoFutureSource(base.path("characters"), first.getEpisode().getEpisodeNo());
            assertNoFutureSource(base.path("worldSettings"), first.getEpisode().getEpisodeNo());
        }
        UUID runId = UUID.randomUUID();
        UUID predecessor = null;
        int previousEpisodeNo = -1;
        for (int index = 0; index < jobs.size(); index++) {
            AnalysisJob job = jobs.get(index);
            if (!Objects.equals(job.getWork().getId(), first.getWork().getId())
                    || job.getSourceKind() != first.getSourceKind()
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
            automaticPredecessors(job);
            if (job.getAutomaticInputState() == null || !job.getAutomaticInputState().isObject()) throw conflict();
            return job.getAutomaticInputState().deepCopy();
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
        JsonNode input = getInputState(job);
        if (job.getInputStateHash() == null || !journal.hash(input).equals(job.getInputStateHash())
                || job.getJournalStatus() == AnalysisJournalStatus.INVALIDATED
                || job.getJournalStatus() == AnalysisJournalStatus.INCOMPLETE) {
            throw conflict();
        }
        return job.getStateJournal() == null ? input : apply(input, readChanges(requireEnvelope(job)));
    }

    @Override
    public PreparedState prepareState(AnalysisJob job) {
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
        if (job.isAutomaticReview() && job.getAutomaticInputState() == null) {
            automaticPredecessors(job);
            if (!job.isSettingBookSource()) assertNoFutureConfirmedHistory(job.getWork().getId(), job.getSourceEpisodeNo());
            ObjectNode input = journal.emptyState();
            Set<String> domains = new HashSet<>();
            for (AnalysisStateSource source : stateSources) {
                JsonNode captured = source.capture(job.getWork());
                if (!List.of("characters", "worldSettings", "references").contains(source.domain())
                        || !domains.add(source.domain()) || captured == null || !captured.isObject()) throw conflict();
                input.set(source.domain(), captured.deepCopy());
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
        String hash = journal.hash(getInputState(job));
        if (job.getInputStateHash() != null && !hash.equals(job.getInputStateHash())) {
            throw conflict();
        }
        job.prepareOrderedInput(hash);
        if (job.getStateJournal() == null) {
            job.replacePendingJournal(envelope(job));
        } else {
            getProjectedState(job);
        }
    }

    @Override
    public void assertValidInput(AnalysisJob job) {
        if (job.isSettingBookSource() && !job.hasCurrentSourceVersion()) throw conflict();
        if (!job.isOrderedProvisional()) return;
        if (job.getJournalStatus() != AnalysisJournalStatus.PENDING || !job.hasCurrentSourceVersion()
                || job.getInputStateHash() == null
                || !job.getInputStateHash().equals(journal.hash(getInputState(job)))) {
            throw conflict();
        }
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
        JsonNode input = getInputState(job);
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
        for (AnalysisStateChange change : changes) {
            AnalysisStateChange old = current.putIfAbsent(change.eventId(), change);
            if (old != null && !old.equals(change)) {
                throw conflict();
            }
        }
        List<AnalysisStateChange> merged = List.copyOf(current.values());
        apply(getInputState(job), merged);
        record.set("changes", journal.toJson(merged));
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
        record.put("outputStateHash", journal.hash(getProjectedState(job)));
        job.replacePendingJournal(record);
        job.sealJournal();
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
        eventPublisher.publishEvent(new org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents.EpisodeSourceChanged(workId, episodeId));
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

    private void requireOrdered(AnalysisJob job) {
        if (!job.isOrderedProvisional() || job.getRunGeneration() == null
                || job.getRunSequence() == null || job.getAnalysisRunId() == null) {
            throw conflict();
        }
    }

    private List<AnalysisJob> automaticPredecessors(AnalysisJob job) {
        List<AnalysisJob> chain = analysisJobRepository.findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(
                job.getAnalysisRunId(), job.getRunGeneration());
        List<AnalysisJob> predecessors = new ArrayList<>();
        UUID previousId = null;
        int sequence = 0;
        int previousEpisode = -1;
        for (AnalysisJob member : chain) {
            if (!member.isAutomaticReview() || !Objects.equals(member.getWork().getId(), job.getWork().getId())
                    || !Objects.equals(member.getRunSequence(), sequence)
                    || !Objects.equals(member.getPredecessorJobId(), previousId)
                    || !member.hasCurrentSourceVersion() || member.getSourceKind() != job.getSourceKind()
                    || member.isSettingBookSource() && (chain.size() != 1 || sequence != 0)
                    || !member.isSettingBookSource() && member.getSourceEpisodeNo() <= previousEpisode) throw conflict();
            if (member.getId().equals(job.getId())) return List.copyOf(predecessors);
            if (sequence >= job.getRunSequence()) throw conflict();
            if (member.getStatus() != AnalysisJobStatus.SUCCEEDED
                    || member.getJournalStatus() != AnalysisJournalStatus.SEALED
                    || member.getAutomaticAppliedAt() == null) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_PREDECESSOR_INCOMPLETE);
            }
            predecessors.add(member);
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
        record.put("formatVersion", AnalysisStateJournal.FORMAT_VERSION);
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
                || record.path("formatVersion").asInt(-1) != AnalysisStateJournal.FORMAT_VERSION
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
            return journal.fromJson(record.path("changes"));
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

    private AppException conflict() {
        return new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
    }
}
