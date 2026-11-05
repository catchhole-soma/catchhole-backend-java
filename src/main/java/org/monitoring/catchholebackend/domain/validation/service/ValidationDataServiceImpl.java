package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationReportRepository;
import org.monitoring.catchholebackend.global.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검수 Worker의 고정 출처·설정 읽기. legacy report는 저장 baseline을, #381 live report는 현재 확정 설정(ValidationLiveSettings)을 읽는다.
 * 원문 I/O 전후 scalar projection은 JPA 1차 캐시와 분리한다.
 */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ValidationDataServiceImpl implements ValidationDataService {
    private final ValidationReportRepository reports;
    private final org.monitoring.catchholebackend.domain.validation.repository.ValidationBaselineSourceRepository frozenSources;
    private final AnalysisStateJournal journal;
    private final ValidationDataMapper mapper;
    private final EntityManager em;
    private final org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess execution;
    private final org.monitoring.catchholebackend.domain.validation.processor.ValidationSourceTexts texts;
    private final org.monitoring.catchholebackend.domain.validation.processor.ValidationLiveSettings liveSettings;
    private final org.monitoring.catchholebackend.domain.validation.processor.ValidationSourceManifests sourceManifests;

    @Override
    public void validateValidationBaseline(Long memberId, UUID workId, UUID reportId) {
        var r=reports.findByIdAndWorkId(reportId,workId).filter(x->x.getMember().getId().equals(memberId)).orElseThrow(()->new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND));
        execution.currentInput(r,false);
        if(reports.isLiveSettingsReport(reportId)) {validateLiveBaseline(r,workId);return;}
        var seed=r.getBaselineSeed();
        if(seed==null||r.getBaselineHash()==null||!r.getBaselineHash().equals(r.getBaselineFingerprint())||!r.getBaselineHash().equals(journal.hash(seed))
                ||!seed.path("characters").isObject()||!seed.path("worldSettings").isObject()||!seed.path("worldHistory").isArray()||!seed.path("sources").isArray()
                ||!workId.toString().equals(seed.path("workId").asText())||memberId.longValue()!=seed.path("memberId").asLong()
                ||!r.getEpisode().getId().toString().equals(seed.path("target").path("episodeId").asText())||r.getSourceContentRevision()!=seed.path("target").path("contentRevision").asLong()
                ||r.getSourceEpisodeNo()!=seed.path("target").path("episodeNo").asInt()||!r.getSourceContentHash().equals(seed.path("target").path("contentHash").asText())
                ||!r.getSourceContentS3Key().equals(seed.path("target").path("contentS3Key").asText())||!Objects.equals(r.getSourceContentS3Version(),nullable(seed.path("target"),"contentS3Version")))throw changed();
        var stored=new HashSet<String>();for(var source:frozenSources.findAllByReportId(reportId))stored.add(journal.hash(source.getSourceManifest()));
        var expected=new HashSet<String>();for(var source:seed.path("sources"))expected.add(journal.hash(source));
        if(stored.size()!=seed.path("sources").size()||!stored.equals(expected))throw changed();
        for(var source:mapper.toSourceReferences(r).entrySet())if(!"READY".equals(texts.status(workId,r.getSourceEpisodeNo(),source.getValue(),source.getKey().equals("T1"))))throw changed();
    }

    /**
     * #381 seed 없는 report. 세 hash 열이 같은 설정 버전 fingerprint이고 지금 다시 계산한 값과 같아야 하며(이벤트 없이 확정 설정을 바꾼 쓰기 감지),
     * 대상 원고와 저장 manifest의 모든 출처가 지금도 같은 원문으로 읽힐 수 있어야 한다. 설정 행은 읽지 않는다.
     */
    private void validateLiveBaseline(ValidationReport r, UUID workId) {
        String fingerprint=r.getBaselineHash();
        if(fingerprint==null||!fingerprint.equals(r.getBaselineFingerprint())||!fingerprint.equals(r.getConfirmedStateHash())
                ||!fingerprint.equals(liveSettings.fingerprint(workId)))throw changed();
        for(var source:mapper.toSourceReferences(r,sourceManifests.manifests(r.getId())).entrySet())
            if(!"READY".equals(texts.status(workId,r.getSourceEpisodeNo(),source.getValue(),source.getKey().equals("T1"))))throw changed();
    }

    @Override
    public JsonNode readValidationData(Long memberId, UUID workId, UUID reportId, ValidationDataRequest request) {
        return readValidationData(memberId,workId,reportId,request,null,null);
    }
    @Override
    public JsonNode readValidationData(Long memberId, UUID workId, UUID reportId, ValidationDataRequest request, UUID jobId, UUID leaseToken) {
        if (memberId == null || request == null || request.offset() < 0 || request.limit() < 1 || request.limit() > 100)
            throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        boolean live = requireCurrentReport(memberId, workId, reportId, request, jobId, leaseToken);
        ValidationReport report = reports.findById(reportId).orElseThrow(() -> new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND));
        // #381: live report(seed 없음)는 현재 확정 설정을 읽는다. baselineHash는 설정 버전 fingerprint이며 requireCurrentReport가 요청 값·지금 값과 대조한다.
        if (!live && (report.getBaselineSeed() == null || report.getBaselineEntries() == null
                || !Objects.equals(journal.hash(report.getBaselineSeed()), request.expectedBaselineHash()))) throw changed();
        var manifests = sourceManifests.manifests(reportId);
        var refs = mapper.toSourceReferences(report, manifests);
        ObjectNode response = mapper.toResponse(report, sourceManifests.manifestHash(manifests));
        switch (request.operation()) {
            case "INPUT" -> {
                response.set("target", refs.get("T1"));
                var sources = response.putArray("sources"); refs.forEach((ref, source) -> { if (!ref.equals("T1")) sources.add(source); });
                response.put("entryCount", live ? liveSettings.index(workId).subjects().size() : report.getBaselineEntries().size());
                response.set("modelSourceCatalogue",mapper.toValidationSourceCatalogue(refs));
            }
            case "SUBJECTS" -> {
                // #349 grid v4: the Worker reads the subject list once at start; there is no paging (offset/limit are ignored).
                // live: 색인 행(이름·별칭)만 scalar로 읽는다.
                JsonNode entries = live ? liveSettings.entries(liveSettings.index(workId), Set.of()) : report.getBaselineEntries();
                var subjects=mapper.toValidationSubjectCatalogue(entries,refs);
                response.set("subjects",subjects);response.put("subjectCount",subjects.size()).put("entryCount",entries.size());
            }
            case "SETTINGS" -> {
                // #349 grid v4: current rows of the requested subjects only (characters/worldSettings), in B order; worldHistory is never returned.
                var wanted=request.subjectRefs();
                if(wanted==null||wanted.isEmpty()||wanted.size()>100||wanted.stream().anyMatch(ref->ref==null||!ref.matches("S[1-9][0-9]*")))
                    throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
                JsonNode entries;
                if(live) {
                    // live: 요청 주체(최대 100개)만 id로 전체 상태를 읽고 나머지는 색인 행으로 둔다(S_n == B_n).
                    var index=liveSettings.index(workId);
                    if(wanted.stream().anyMatch(ref->index.find(ref).isEmpty()))throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
                    entries=liveSettings.entries(index,wanted);
                } else {
                    entries=report.getBaselineEntries();
                    if(!new HashSet<>(ValidationDataMapper.toSubjectRefs(entries).values()).containsAll(wanted))throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
                }
                var rows=mapper.toValidationCurrentSettings(entries,refs,wanted);var settings=response.putArray("settings");rows.forEach(settings::add);
                var proofs=response.putArray("authorCorrectionProofs");
                for(var row:rows) {
                    var entry=entries.path(Integer.parseInt(row.path("entry_ref").asText().substring(1))-1);
                    if(entry.path("state").path("explicitAuthorCorrections").isArray())proofs.addObject().put("entryRef",row.path("entry_ref").asText()).set("proofs",entry.path("state").path("explicitAuthorCorrections").deepCopy());
                }
            }
            case "VALIDATE" -> {
                for (var entry : refs.entrySet()) {
                    String status = texts.status(workId, report.getSourceEpisodeNo(), entry.getValue(), entry.getKey().equals("T1"));
                    if (!status.equals("READY")) return response.put("status", status);
                }
            }
            case "READ_SOURCE" -> {
                ObjectNode source = refs.get(request.sourceRef());
                if (source == null) throw new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND);
                if (!Objects.equals(request.expectedSourceHash(), source.path("contentHash").asText())
                        || !Objects.equals(request.expectedSourceVersion(), nullable(source, "contentS3Version"))) return response.put("status", "SOURCE_CHANGED");
                // 고정 출처 전문 읽기와 hash 확인은 작가 상세의 앞뒤 줄과 같은 경로(ValidationSourceTexts)다. 저장소 GET 직후 report·lease를 다시 확인한다.
                var read = texts.read(workId, report.getSourceEpisodeNo(), source, request.sourceRef().equals("T1"),
                        () -> requireCurrentReport(memberId, workId, reportId, request, jobId, leaseToken));
                if (!read.ready()) return response.put("status", read.status());
                String text = read.text();
                int length = text.codePointCount(0, text.length());
                int start = request.start() == null ? 0 : request.start();
                int end = request.end() == null ? length : request.end();
                if (start < 0 || end < start || end > length) return response.put("status", "INVALID_RANGE");
                response.put("sourceRef", request.sourceRef()).put("contentHash", source.path("contentHash").asText())
                        .put("start", start).put("end", end).put("totalCodePoints", length)
                        .put("text", text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end)));
            }
            default -> throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        }
        requireCurrentReport(memberId, workId, reportId, request, jobId, leaseToken);
        return response;
    }

    /** 읽기 전후의 현재성 확인. live report면 true를 돌려준다(seed 여부는 JSON을 읽지 않고 DB의 IS NULL로 판단). */
    private boolean requireCurrentReport(Long member, UUID work, UUID report, ValidationDataRequest request, UUID jobId, UUID leaseToken) {
        execution.requireRead(report,jobId,leaseToken);
        var state = em.createQuery("""
                select r.baselineHash, r.sourceContentHash, cast(r.baselineStatus as string),
                       cast(r.status as string), cast(r.work.lifecycleStatus as string), cast(r.member.status as string),
                       r.current, r.sourceContentRevision, r.episode.contentRevision,
                       case when r.baselineSeed is null then true else false end
                from ValidationReport r where r.id = :report and r.work.id = :work and r.member.id = :member
                """, Object[].class).setParameter("report", report).setParameter("work", work).setParameter("member", member)
                .getResultStream().findFirst().orElseThrow(() -> new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND));
        if (!Objects.equals(state[0], request.expectedBaselineHash()) || !Objects.equals(state[1], request.expectedTargetHash())
                || !"READY".equals(state[2]) || "INVALIDATED".equals(state[3]) || !"ACTIVE".equals(state[4]) || !"ACTIVE".equals(state[5])
                || !Boolean.TRUE.equals(state[6]) || !Objects.equals(state[7],state[8])) throw changed();
        boolean live = Boolean.TRUE.equals(state[9]);
        // #381: 접수 뒤 이벤트 없이 확정 설정 version을 바꾼 쓰기를 알아보는 안전장치. 기존 409이며 Worker는 INPUT_CHANGED로 멈춘다.
        if (live && !Objects.equals(liveSettings.fingerprint(work), state[0])) throw changed();
        return live;
    }

    private String nullable(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : null; }
    private AppException changed() { return new AppException(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT); }
}
