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

/** 저장 baseline만 읽는다. 원문 I/O 전후 scalar projection은 JPA 1차 캐시와 분리한다. */
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
        requireCurrentReport(memberId, workId, reportId, request, jobId, leaseToken);
        ValidationReport report = reports.findById(reportId).orElseThrow(() -> new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND));
        // #381: live report(seed 없음)의 baselineHash는 설정 버전 fingerprint이며 requireCurrentReport가 요청 값과 대조한다.
        boolean live = reports.isLiveSettingsReport(reportId);
        if (!live && (report.getBaselineSeed() == null || report.getBaselineEntries() == null
                || !Objects.equals(journal.hash(report.getBaselineSeed()), request.expectedBaselineHash()))) throw changed();
        var manifests = sourceManifests.manifests(reportId);
        var refs = mapper.toSourceReferences(report, manifests);
        ObjectNode response = mapper.toResponse(report, sourceManifests.manifestHash(manifests));
        // 기준 항목 조회(ENTRIES·SUBJECTS·SETTINGS)의 live 경로는 다음 단계(#381 Step 6)에서 현재 확정 설정 읽기로 연결한다.
        if (live && Set.of("ENTRIES", "SUBJECTS", "SETTINGS").contains(request.operation())) throw changed();
        switch (request.operation()) {
            case "INPUT" -> {
                response.set("target", refs.get("T1"));
                var sources = response.putArray("sources"); refs.forEach((ref, source) -> { if (!ref.equals("T1")) sources.add(source); });
                response.put("entryCount", live ? liveSettings.index(workId).subjects().size() : report.getBaselineEntries().size());
                response.set("modelSourceCatalogue",mapper.toValidationSourceCatalogue(refs));
            }
            case "ENTRIES" -> {
                response.set("entries", mapper.toEntries(report.getBaselineEntries(), request.offset(), request.limit()));
                var projected=mapper.toValidationSettings(report.getBaselineEntries(),refs);var models=response.putArray("modelSettings");
                projected.values().stream().skip(request.offset()).limit(request.limit()).forEach(models::add);
                var proofs=response.putArray("authorCorrectionProofs");int index=0;
                for(var entry:report.getBaselineEntries()) {
                    index++;if(index>request.offset()&&index<=(long)request.offset()+request.limit()&&entry.path("state").path("explicitAuthorCorrections").isArray())
                        proofs.addObject().put("entryRef","B"+index).set("proofs",entry.path("state").path("explicitAuthorCorrections").deepCopy());
                }
                int next = (int) Math.min(report.getBaselineEntries().size(), (long) request.offset() + request.limit());
                response.put("nextOffset", next).put("hasMore", next < report.getBaselineEntries().size());
            }
            case "SUBJECTS" -> {
                // #349 grid v4: the Worker reads the subject list once at start; there is no paging (offset/limit are ignored).
                var subjects=mapper.toValidationSubjectCatalogue(report.getBaselineEntries(),refs);
                response.set("subjects",subjects);response.put("subjectCount",subjects.size()).put("entryCount",report.getBaselineEntries().size());
            }
            case "SETTINGS" -> {
                // #349 grid v4: current rows of the requested subjects only (characters/worldSettings), in B order; worldHistory is never returned.
                var wanted=request.subjectRefs();var known=new HashSet<String>();
                ValidationDataMapper.toSubjectRefs(report.getBaselineEntries()).values().forEach(known::add);
                if(wanted==null||wanted.isEmpty()||wanted.size()>100||wanted.stream().anyMatch(ref->ref==null||!ref.matches("S[1-9][0-9]*")||!known.contains(ref)))
                    throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
                var rows=mapper.toValidationCurrentSettings(report.getBaselineEntries(),refs,wanted);var settings=response.putArray("settings");rows.forEach(settings::add);
                var proofs=response.putArray("authorCorrectionProofs");
                for(var row:rows) {
                    var entry=report.getBaselineEntries().path(Integer.parseInt(row.path("entry_ref").asText().substring(1))-1);
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

    private void requireCurrentReport(Long member, UUID work, UUID report, ValidationDataRequest request, UUID jobId, UUID leaseToken) {
        execution.requireRead(report,jobId,leaseToken);
        var state = em.createQuery("""
                select r.baselineHash, r.sourceContentHash, cast(r.baselineStatus as string),
                       cast(r.status as string), cast(r.work.lifecycleStatus as string), cast(r.member.status as string),
                       r.current, r.sourceContentRevision, r.episode.contentRevision
                from ValidationReport r where r.id = :report and r.work.id = :work and r.member.id = :member
                """, Object[].class).setParameter("report", report).setParameter("work", work).setParameter("member", member)
                .getResultStream().findFirst().orElseThrow(() -> new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND));
        if (!Objects.equals(state[0], request.expectedBaselineHash()) || !Objects.equals(state[1], request.expectedTargetHash())
                || !"READY".equals(state[2]) || "INVALIDATED".equals(state[3]) || !"ACTIVE".equals(state[4]) || !"ACTIVE".equals(state[5])
                || !Boolean.TRUE.equals(state[6]) || !Objects.equals(state[7],state[8])) throw changed();
    }

    private String nullable(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : null; }
    private AppException changed() { return new AppException(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT); }
}
