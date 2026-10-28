package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationReportRepository;
import org.monitoring.catchholebackend.global.exception.*;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장 baseline만 읽는다. 원문 I/O 전후 scalar projection은 JPA 1차 캐시와 분리한다. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ValidationDataServiceImpl implements ValidationDataService {
    private static final int MAX_SOURCE_BYTES = 2_000_000; // 기존 검색·인용 reader와 동일한 읽기 지원 범위
    private final ValidationReportRepository reports;
    private final EpisodeSourcePurgeRequestRepository purges;
    private final ObjectStorageService storage;
    private final AnalysisStateJournal journal;
    private final ValidationDataMapper mapper;
    private final EntityManager em;

    @Override
    public JsonNode readValidationData(Long memberId, UUID workId, UUID reportId, ValidationDataRequest request) {
        if (memberId == null || request == null || request.offset() < 0 || request.limit() < 1 || request.limit() > 100)
            throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        requireCurrentReport(memberId, workId, reportId, request);
        ValidationReport report = reports.findById(reportId).orElseThrow(() -> new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND));
        if (report.getBaselineSeed() == null || report.getBaselineEntries() == null
                || !Objects.equals(journal.hash(report.getBaselineSeed()), request.expectedBaselineHash())) throw changed();
        var refs = mapper.toSourceReferences(report);
        ObjectNode response = mapper.toResponse(report);
        switch (request.operation()) {
            case "INPUT" -> {
                response.set("target", refs.get("T1"));
                var sources = response.putArray("sources"); refs.forEach((ref, source) -> { if (!ref.equals("T1")) sources.add(source); });
                response.put("entryCount", report.getBaselineEntries().size());
            }
            case "ENTRIES" -> {
                response.set("entries", mapper.toEntries(report.getBaselineEntries(), request.offset(), request.limit()));
                int next = (int) Math.min(report.getBaselineEntries().size(), (long) request.offset() + request.limit());
                response.put("nextOffset", next).put("hasMore", next < report.getBaselineEntries().size());
            }
            case "VALIDATE" -> {
                for (var entry : refs.entrySet()) {
                    String status = sourceStatus(workId, report.getSourceEpisodeNo(), entry.getValue(), entry.getKey().equals("T1"));
                    if (!status.equals("READY")) return response.put("status", status);
                }
            }
            case "READ_SOURCE" -> {
                ObjectNode source = refs.get(request.sourceRef());
                if (source == null) throw new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND);
                if (!Objects.equals(request.expectedSourceHash(), source.path("contentHash").asText())
                        || !Objects.equals(request.expectedSourceVersion(), nullable(source, "contentS3Version"))) return response.put("status", "SOURCE_CHANGED");
                String before = sourceStatus(workId, report.getSourceEpisodeNo(), source, request.sourceRef().equals("T1"));
                if (!before.equals("READY")) return response.put("status", before);
                String text;
                try { text = storage.getText(source.path("contentS3Key").asText()); }
                catch (RuntimeException exception) { return response.put("status", "SOURCE_UNREADABLE"); }
                requireCurrentReport(memberId, workId, reportId, request);
                String after = sourceStatus(workId, report.getSourceEpisodeNo(), source, request.sourceRef().equals("T1"));
                if (!after.equals("READY")) return response.put("status", after);
                if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) return response.put("status", "SOURCE_UNREADABLE");
                if (!storage.contentHash(text).equals(source.path("contentHash").asText())) return response.put("status", "SOURCE_CHANGED");
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
        requireCurrentReport(memberId, workId, reportId, request);
        return response;
    }

    private void requireCurrentReport(Long member, UUID work, UUID report, ValidationDataRequest request) {
        var state = em.createQuery("""
                select r.baselineHash, r.sourceContentHash, cast(r.baselineStatus as string),
                       cast(r.status as string), cast(r.work.lifecycleStatus as string), cast(r.member.status as string)
                from ValidationReport r where r.id = :report and r.work.id = :work and r.member.id = :member
                """, Object[].class).setParameter("report", report).setParameter("work", work).setParameter("member", member)
                .getResultStream().findFirst().orElseThrow(() -> new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND));
        if (!Objects.equals(state[0], request.expectedBaselineHash()) || !Objects.equals(state[1], request.expectedTargetHash())
                || !"READY".equals(state[2]) || "INVALIDATED".equals(state[3]) || !"ACTIVE".equals(state[4]) || !"ACTIVE".equals(state[5])) throw changed();
    }

    private String sourceStatus(UUID work, int targetNo, JsonNode source, boolean target) {
        String kind = source.path("sourceKind").asText();
        Object[] state;
        if (kind.equals("EPISODE")) {
            UUID id = UUID.fromString(source.path("episodeId").asText());
            state = em.createQuery("""
                    select e.contentS3Key, e.contentHash, e.contentS3Version, e.episodeNo, cast(e.status as string)
                    from Episode e where e.id = :id and e.work.id = :work
                    """, Object[].class).setParameter("id", id).setParameter("work", work).getResultStream().findFirst().orElse(null);
            if (state == null || "ARCHIVED".equals(state[4]) || purges.existsByEpisodeId(id)) return "SOURCE_PURGED";
            if (!Objects.equals(state[3], source.path("episodeNo").asInt())
                    || target && (int) state[3] != targetNo || !target && (int) state[3] >= targetNo) return "SOURCE_CHANGED";
        } else if (kind.equals("SETTING_BOOK") && !target) {
            state = em.createQuery("""
                    select r.contentS3Key, r.contentHash, r.contentS3Version, r.revisionNo,
                           cast(r.referenceTimeKind as string), r.referenceEpisodeNo, r.settingBook.id
                    from SettingBookRevision r where r.id = :id and r.settingBook.batch.work.id = :work
                    """, Object[].class).setParameter("id", UUID.fromString(source.path("revisionId").asText()))
                    .setParameter("work", work).getResultStream().findFirst().orElse(null);
            if (state == null) return "SOURCE_PURGED";
            if (!Objects.equals(state[3], source.path("revisionNo").asInt()) || !Objects.equals(state[4], source.path("referenceTimeKind").asText())
                    || !Objects.equals(state[5], source.path("referenceEpisodeNo").isNumber() ? source.path("referenceEpisodeNo").asInt() : null)
                    || !Objects.equals(state[6].toString(), source.path("settingBookId").asText())
                    || "EPISODE".equals(state[4]) && (int) state[5] >= targetNo) return "SOURCE_CHANGED";
        } else return "SOURCE_CHANGED";
        if (!Objects.equals(state[0], source.path("contentS3Key").asText()) || !Objects.equals(state[1], source.path("contentHash").asText())
                || !Objects.equals(state[2], nullable(source, "contentS3Version"))) return "SOURCE_CHANGED";
        return "READY";
    }
    private String nullable(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : null; }
    private AppException changed() { return new AppException(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT); }
}
