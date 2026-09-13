package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateChronology;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateGroupNameNormalizer;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.springframework.stereotype.Component;

/** 회차별 후보 게시와 캐릭터 그룹 비교 Job 사이의 내구성 있는 인계를 조정한다. */
@Component
@RequiredArgsConstructor
public class CharacterFactComparisonJobCoordinator {

    private static final List<AnalysisJobStatus> ACTIVE_JOB_STATUSES = List.of(
            AnalysisJobStatus.PENDING,
            AnalysisJobStatus.RUNNING
    );
    private final UploadBatchRepository uploadBatchRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final SettingCandidateRepository settingCandidateRepository;
    private final AiTokenService aiTokenService;

    public void handoffIfInputComplete(AnalysisJob sourceJob) {
        if (sourceJob.isOrderedProvisional()
                || sourceJob.getJobType() != AnalysisJobType.SETTING_EXTRACTION
                || sourceJob.getBatch() == null) {
            return;
        }
        UUID batchId = sourceJob.getBatch().getId();
        uploadBatchRepository.findByIdForUpdate(batchId).orElseThrow();
        List<AnalysisJob> sourceJobs = analysisJobRepository
                .findAllByBatchIdAndJobTypeOrderByCreatedAtAsc(batchId, AnalysisJobType.SETTING_EXTRACTION);
        if (sourceJobs.stream().anyMatch(this::canStillPublishCandidates)) {
            return;
        }

        List<SettingCandidate> candidates = lockPendingBatchCandidates(sourceJob.getWork().getId(), batchId);
        Map<ScopeKey, List<SettingCandidate>> groups = new LinkedHashMap<>();
        for (SettingCandidate candidate : candidates) {
            scopeKey(candidate).ifPresent(key -> groups
                    .computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(candidate));
        }
        groups.values().forEach(group -> schedule(group, false, null));
    }

    public void enqueueIfNeeded(Long memberId, SettingCandidate seed) {
        if (!isEligible(seed) || seed.getAnalysisJob() == null || seed.getAnalysisJob().getBatch() == null) {
            return;
        }
        if (seed.getComparisonStatus() == CharacterFactComparisonStatus.COMPLETED) {
            return;
        }
        UUID batchId = seed.getAnalysisJob().getBatch().getId();
        List<SettingCandidate> group = candidatesInScope(
                lockPendingBatchCandidates(seed.getWork().getId(), batchId),
                scopeKey(seed).orElseThrow()
        );
        schedule(group, true, memberId);
    }

    public List<ScopeRef> scopeRefs(List<SettingCandidate> candidates) {
        return candidates.stream()
                .map(this::scopeRef)
                .flatMap(java.util.Optional::stream)
                .distinct()
                .toList();
    }

    public void enqueueScopes(Long memberId, List<ScopeRef> scopes) {
        for (ScopeRef scope : scopes.stream().distinct().toList()) {
            List<SettingCandidate> group = candidatesInScope(
                    lockPendingBatchCandidates(scope.workId(), scope.batchId()),
                    scope.key()
            );
            schedule(group, true, memberId);
        }
    }

    public void invalidateReopenedInputScopes(List<ScopeRef> scopes, Set<UUID> removedCandidateIds) {
        for (ScopeRef scope : scopes.stream().distinct().toList()) {
            candidatesInScope(
                    lockPendingBatchCandidates(scope.workId(), scope.batchId()),
                    scope.key()
            ).stream()
                    .filter(candidate -> !removedCandidateIds.contains(candidate.getId()))
                    .forEach(candidate -> candidate.markRecomparisonRequired(
                            "원문 분석 입력이 다시 열려 그룹 비교가 필요합니다."
                    ));
        }
    }

    public List<SettingCandidate> lockScopeCandidates(AnalysisJob comparisonJob) {
        SettingCandidate seed = comparisonJob.getSettingCandidate();
        if (seed == null) {
            return List.of();
        }
        if (comparisonJob.getCharacterComparisonInputHash() == null
                || comparisonJob.getBatch() == null) {
            return List.of(seed);
        }
        ScopeKey key = scopeKey(seed).orElse(null);
        if (key == null) {
            return List.of();
        }
        return candidatesInScope(
                lockPendingBatchCandidates(comparisonJob.getWork().getId(), comparisonJob.getBatch().getId()),
                key
        );
    }

    public boolean hasCurrentInput(AnalysisJob comparisonJob, List<SettingCandidate> candidates) {
        return comparisonJob.getCharacterComparisonInputHash() == null
                || Objects.equals(comparisonJob.getCharacterComparisonInputHash(), inputHash(candidates));
    }

    public String inputHash(List<SettingCandidate> candidates) {
        StringBuilder input = new StringBuilder();
        for (SettingCandidate candidate : SettingCandidateChronology.sorted(candidates)) {
            appendScalar(input, candidate.getId());
            appendScalar(input, candidate.getMatchedCharacterId());
            appendScalar(input, SettingCandidateGroupNameNormalizer.toGroupKey(candidate.getEntityName()));
            appendScalar(input, candidate.getSourceChunkId());
            appendScalar(input, candidate.getSourceContentS3Key());
            appendScalar(input, candidate.getAttributeName());
            appendScalar(input, candidate.getValueType());
            appendCanonicalJson(input, candidate.getValueJson());
            appendCanonicalJson(input, candidate.getEvidenceSpans());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void appendScalar(StringBuilder target, Object value) {
        if (value == null) {
            target.append("N;");
            return;
        }
        String text = value.toString();
        target.append('S').append(text.length()).append(':').append(text).append(';');
    }

    private void appendCanonicalJson(StringBuilder target, JsonNode value) {
        if (value == null || value.isNull()) {
            target.append("JN;");
            return;
        }
        if (value.isObject()) {
            target.append("JO{");
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> {
                appendScalar(target, name);
                appendCanonicalJson(target, value.get(name));
            });
            target.append("};");
            return;
        }
        if (value.isArray()) {
            target.append("JA[");
            value.forEach(item -> appendCanonicalJson(target, item));
            target.append("];");
            return;
        }
        target.append('J');
        appendScalar(target, value.toString());
    }

    private void schedule(List<SettingCandidate> rawGroup, boolean checkQuota, Long memberId) {
        List<SettingCandidate> group = SettingCandidateChronology.sorted(rawGroup).stream()
                .filter(this::isEligible)
                .toList();
        if (group.isEmpty()) {
            return;
        }
        String inputHash = inputHash(group);
        UUID batchId = group.getFirst().getAnalysisJob().getBatch().getId();
        ScopeKey key = scopeKey(group.getFirst()).orElseThrow();
        List<AnalysisJob> activeJobs = analysisJobRepository.findAllActiveComparisonJobs(
                batchId,
                AnalysisJobType.CHARACTER_FACT_COMPARISON,
                ACTIVE_JOB_STATUSES
        );
        boolean currentExists = false;
        for (AnalysisJob activeJob : activeJobs) {
            SettingCandidate activeSeed = activeJob.getSettingCandidate();
            if (activeSeed == null || !scopeKey(activeSeed).map(key::equals).orElse(false)) {
                continue;
            }
            if (Objects.equals(activeJob.getCharacterComparisonInputHash(), inputHash)) {
                currentExists = true;
            }
        }
        if (currentExists) {
            return;
        }
        if (checkQuota) {
            aiTokenService.ensureComparisonCanStart(Objects.requireNonNull(memberId));
        }
        for (SettingCandidate candidate : group) {
            if (candidate.getComparisonStatus() == CharacterFactComparisonStatus.PROCESSING) {
                candidate.recoverExpiredComparison();
            }
            if (candidate.getComparisonStatus() != CharacterFactComparisonStatus.PENDING) {
                candidate.requestComparison();
            }
        }
        analysisJobRepository.save(AnalysisJob.createCharacterFactComparison(group.getFirst(), inputHash));
    }

    private List<SettingCandidate> lockPendingBatchCandidates(UUID workId, UUID batchId) {
        return settingCandidateRepository.findAllPendingInBatchForUpdate(
                workId,
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW
        );
    }

    private List<SettingCandidate> candidatesInScope(List<SettingCandidate> candidates, ScopeKey key) {
        return SettingCandidateChronology.sorted(candidates).stream()
                .filter(this::isEligible)
                .filter(candidate -> scopeKey(candidate).map(key::equals).orElse(false))
                .toList();
    }

    private boolean canStillPublishCandidates(AnalysisJob sourceJob) {
        if (sourceJob.getStatus() == AnalysisJobStatus.SUCCEEDED
                || sourceJob.getStatus() == AnalysisJobStatus.FAILED
                || sourceJob.getStatus() == AnalysisJobStatus.CANCELED) {
            return false;
        }
        return !sourceJob.hasHandedOffCharacterComparisons();
    }

    private boolean isEligible(SettingCandidate candidate) {
        return !candidate.isCharacterDiscovery()
                && candidate.getReviewStatus() == SettingCandidateReviewStatus.PENDING_REVIEW
                && candidate.getMatchStatus() != SettingCandidateMatchStatus.AMBIGUOUS
                && candidate.getAnalysisJob() != null
                && !candidate.getAnalysisJob().isOrderedProvisional()
                && candidate.getAnalysisJob().getBatch() != null;
    }

    private java.util.Optional<ScopeKey> scopeKey(SettingCandidate candidate) {
        if (!isEligible(candidate)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(candidate.getMatchedCharacterId() == null
                ? new ScopeKey("NEW", SettingCandidateGroupNameNormalizer.toGroupKey(candidate.getEntityName()))
                : new ScopeKey("EXISTING", candidate.getMatchedCharacterId().toString()));
    }

    private java.util.Optional<ScopeRef> scopeRef(SettingCandidate candidate) {
        if (candidate.getAnalysisJob() == null || candidate.getAnalysisJob().getBatch() == null) {
            return java.util.Optional.empty();
        }
        return scopeKey(candidate).map(key -> new ScopeRef(
                candidate.getWork().getId(),
                candidate.getAnalysisJob().getBatch().getId(),
                key
        ));
    }

    public record ScopeRef(UUID workId, UUID batchId, ScopeKey key) {
    }

    public record ScopeKey(String targetType, String targetId) {
    }
}
