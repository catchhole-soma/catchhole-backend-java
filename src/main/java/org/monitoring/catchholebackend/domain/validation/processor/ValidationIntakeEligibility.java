package org.monitoring.catchholebackend.domain.validation.processor;

import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationReportRepository;
import org.monitoring.catchholebackend.domain.validation.type.ValidationEligibilityReason;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

import static org.monitoring.catchholebackend.domain.validation.type.ValidationEligibilityReason.*;

/**
 * 신규 회차 검수 접수 자격의 유일한 판정(#314 B1). 상태를 바꾸지 않는다.
 * {@code EpisodeValidationService.start}는 이 결과로 예외를 던지고, 자격 조회({@code getEpisodeValidationEligibility})는 같은 결과를 그대로 읽는다.
 * 요청마다 다른 값(batchId·원고 hash/key/version, 멱등 키)은 접수만 검사하므로 여기서 다루지 않는다.
 *
 * <p>순서: 회차의 현재 report → 회차 존재 → 신규 단일 append 업로드 → 최신 회차 → 원문 파기 대기 → 기존 분석 Job → (접수의 요청 값 검사) →
 * 다음 회차 등록 차단 → 첫 회차 비교 자료. 접수는 Work를 잠근 뒤 호출한다.
 */
@Component
@RequiredArgsConstructor
public class ValidationIntakeEligibility {
    private final EpisodeRepository episodes;
    private final UploadFileRepository files;
    private final EpisodeSourcePurgeRequestRepository purges;
    private final ValidationReportRepository reports;
    private final ValidationNextEpisodeAdmission admission;
    private final EntityManager em;

    /**
     * @param currentReport 같은 작품에 있는 회차의 현재 report(REPORT_EXISTS). 다른 작품의 report는 노출하지 않는다
     * @param episode 작품의 활성 회차. EPISODE_NOT_FOUND·REPORT_EXISTS면 null
     * @param sourceFile 회차의 원본 업로드 파일. 단일 append 판정을 통과했을 때만 값이 있다
     * @param latestEpisode 요청 회차가 작품의 최신 활성 회차인지
     * @param nextEpisodeBlock 작품 단위 다음 회차 등록 차단. NEXT_EPISODE_BLOCKED가 아니어도 항상 계산한다
     */
    public record Judgement(ValidationEligibilityReason reason, ValidationReport currentReport, Episode episode, UploadFile sourceFile, boolean latestEpisode,
            Optional<ValidationNextEpisodeAdmission.Blocking> nextEpisodeBlock) {
        public boolean eligible() { return reason == ELIGIBLE; }
        /** 회차 자체가 신규 검수 대상이다(작품 단위 차단·비교 자료 검사는 남음). 접수는 이때만 요청 값을 대조한다. */
        public boolean targetAccepted() { return reason == ELIGIBLE || reason == NEXT_EPISODE_BLOCKED || reason == COMPARISON_MATERIAL_REQUIRED; }
        /** 접수가 이 사유로 던지는 오류. ELIGIBLE·REPORT_EXISTS는 오류가 아니라 새 report 생성·기존 report 사용이다. */
        public ValidationErrorCode errorCode() { return ValidationIntakeEligibility.errorCode(reason); }
        public AppException toException() {
            if (reason == NEXT_EPISODE_BLOCKED) return nextEpisodeBlock.orElseThrow().toException();
            return new AppException(Objects.requireNonNull(errorCode(), "자격이 있는 판정은 오류가 아닙니다."));
        }
        /** 접수 오류와 같은 문구. ELIGIBLE·REPORT_EXISTS만 별도 안내다. */
        public String message() { return ValidationIntakeEligibility.message(reason); }
    }

    public static ValidationErrorCode errorCode(ValidationEligibilityReason reason) {
        return switch (reason) {
            case ELIGIBLE, REPORT_EXISTS -> null;
            case EPISODE_NOT_FOUND, NOT_SINGLE_APPEND_UPLOAD, NOT_LATEST_EPISODE, SOURCE_PURGE_PENDING, ANALYSIS_ALREADY_STARTED -> ValidationErrorCode.VALIDATION_TARGET_INVALID;
            case NEXT_EPISODE_BLOCKED -> ValidationErrorCode.VALIDATION_NEXT_EPISODE_BLOCKED;
            case COMPARISON_MATERIAL_REQUIRED -> ValidationErrorCode.VALIDATION_COMPARISON_MATERIAL_REQUIRED;
        };
    }

    public static String message(ValidationEligibilityReason reason) {
        return switch (reason) {
            case ELIGIBLE -> "이 회차의 검수를 시작할 수 있습니다.";
            case REPORT_EXISTS -> "이 회차에는 이미 검수가 있습니다. 새로 접수하지 말고 현재 검수를 확인해 주세요.";
            default -> errorCode(reason).getMessage();
        };
    }

    public Judgement judge(UUID workId, UUID episodeId) {
        var block = admission.blocking(workId);
        UUID latestId = episodes.findFirstByWorkIdAndStatusNotOrderByEpisodeNoDesc(workId, EpisodeStatus.ARCHIVED).map(Episode::getId).orElse(null);
        boolean latest = episodeId.equals(latestId);
        var prior = reports.findByEpisodeIdAndCurrentTrue(episodeId);
        if (prior.isPresent()) {
            // 다른 작품의 회차는 존재 여부를 노출하지 않는다. 접수는 이전과 같이 VALIDATION_TARGET_INVALID다.
            if (!prior.get().getWork().getId().equals(workId)) return new Judgement(EPISODE_NOT_FOUND, null, null, null, false, block);
            return new Judgement(REPORT_EXISTS, prior.get(), null, null, latest, block);
        }
        var episode = episodes.findByIdAndWorkIdAndStatusNot(episodeId, workId, EpisodeStatus.ARCHIVED).orElse(null);
        if (episode == null) return new Judgement(EPISODE_NOT_FOUND, null, null, null, false, block);
        var file = episode.getSourceFileId() == null ? null : files.findById(episode.getSourceFileId()).orElse(null);
        if (file == null || file.getFileRole() != UploadFileRole.EPISODE || !file.getBatch().getWork().getId().equals(workId)
                || file.getBatch().getUploadType() != UploadType.SINGLE_EPISODE || !Objects.equals(file.getEpisodeCount(), 1) || !episode.isValidationUploadEligible())
            return new Judgement(NOT_SINGLE_APPEND_UPLOAD, null, episode, null, latest, block);
        if (!latest) return new Judgement(NOT_LATEST_EPISODE, null, episode, file, false, block);
        if (purges.existsByEpisodeId(episode.getId())) return new Judgement(SOURCE_PURGE_PENDING, null, episode, file, true, block);
        if (em.createQuery("select count(j) from AnalysisJob j where j.episode.id = :id or exists (select target.id from AnalysisJob owner join owner.targetEpisodes target where owner.id = j.id and target.id = :id)", Long.class)
                .setParameter("id", episode.getId()).getSingleResult() != 0) return new Judgement(ANALYSIS_ALREADY_STARTED, null, episode, file, true, block);
        if (block.isPresent()) return new Judgement(NEXT_EPISODE_BLOCKED, null, episode, file, true, block);
        boolean priorEpisode = episodes.findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(workId, EpisodeStatus.ARCHIVED)
                .stream().anyMatch(e -> e.getEpisodeNo() < episode.getEpisodeNo());
        if (!priorEpisode && !hasComparisonMaterial(workId)) return new Judgement(COMPARISON_MATERIAL_REQUIRED, null, episode, file, true, block);
        return new Judgement(ELIGIBLE, null, episode, file, true, block);
    }

    private boolean hasComparisonMaterial(UUID workId) {
        return em.createQuery("select count(c) from WorkCharacter c where c.work.id = :id and c.status = 'ACTIVE'", Long.class)
                .setParameter("id", workId).getSingleResult() > 0
                || em.createQuery("select count(s) from WorldSetting s where s.work.id = :id", Long.class)
                        .setParameter("id", workId).getSingleResult() > 0
                || em.createQuery("select count(f) from UploadFile f where f.batch.work.id = :id and f.fileRole = 'SETTING_BOOK' and f.archivedAt is null and f.currentSettingBookRevision is not null", Long.class)
                        .setParameter("id", workId).getSingleResult() > 0;
    }
}
