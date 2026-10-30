package org.monitoring.catchholebackend.domain.relationship.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** 내부 모델 참조와 공개 식별자는 별도 record로 구분한다. 인용 위치는 Unicode code point다. */
public final class RelationshipGraphContract {
    private RelationshipGraphContract() {}

    public enum Operation {
        ADD,
        MERGE,
        UPDATE,
        END,
        EXCLUDE,
        HOLD
    }

    public enum Axis {
        SOCIAL,
        ATTITUDE,
        INTERACTION
    }

    public enum Kind {
        FRIENDSHIP,
        COMPANIONSHIP,
        COOPERATION,
        MENTORSHIP,
        ROMANCE,
        HOSTILITY,
        TRUST,
        DISTRUST,
        AFFECTION,
        ACQUAINTANCE,
        OTHER
    }

    public enum Direction {
        DIRECTED,
        MUTUAL,
        UNDIRECTED
    }

    public enum TemporalScope {
        PRESENT,
        PAST,
        HYPOTHETICAL,
        UNKNOWN
    }

    public enum EdgeStatus {
        ACTIVE,
        ENDED
    }

    public enum AnalysisStatus {
        PENDING,
        PREPARED,
        COMPLETED,
        FAILED,
        APPLIED,
        INVALIDATED
    }

    public enum FailureCode {
        INVALID_RESPONSE,
        EXTRACTION_FAILED,
        COMPARISON_FAILED,
        INPUT_TOO_LARGE
    }

    @Schema(description = "원문의 인물 표기. 이름이 확인되지 않으면 name은 null입니다.")
    public record Endpoint(String name, String mention) {}

    @Schema(name = "RelationshipGraphEvidence", description = "검증한 원문 인용과 전역 Unicode code point 위치")
    public record Evidence(int startOffset, int endOffset, String quote) {}

    @Schema(description = "추출된 관계 관측. 실제 관계 확정과 구분합니다.")
    public record Observation(
            String ref,
            Endpoint source,
            Endpoint target,
            String summary,
            List<Evidence> evidence) {}

    @Schema(description = "관계 비교 준비 요청")
    public record Prepare(
            int formatVersion,
            String sourceHash,
            List<Observation> observations,
            Integer rejectedObservationCount) {}

    @Schema(description = "비교 가능한 인물 참조. 실제 DB 식별자를 모델에 전달하지 않습니다.")
    public record ContextNode(String ref, String name, boolean profileRegistered) {}

    @Schema(description = "출처와 인물 후보 참조를 가진 관측")
    public record ContextObservation(
            String ref,
            Endpoint source,
            Endpoint target,
            String summary,
            List<Evidence> evidence,
            List<String> sourceNodeRefs,
            List<String> targetNodeRefs,
            Integer sourceEpisodeNo) {}

    @Schema(description = "앞 회차까지 공개된 관계 상태")
    public record ContextRelationship(
            String ref,
            String sourceNodeRef,
            String targetNodeRef,
            Axis axis,
            Kind kind,
            String label,
            String summary,
            Direction direction,
            EdgeStatus status,
            int knownFromEpisodeNo,
            int lastChangedEpisodeNo) {}

    @Schema(description = "재시도에도 유지되는 관계 비교 문맥")
    public record Context(
            int formatVersion,
            String sourceHash,
            String contextToken,
            AnalysisStatus status,
            int sourceEpisodeNo,
            List<ContextObservation> observations,
            List<ContextNode> nodes,
            List<ContextRelationship> relationships,
            List<ContextObservation> heldObservations) {}

    @Schema(description = "새 관측 전체를 빠짐없이 처리하는 관계 비교 결정")
    public record Decision(
            List<String> observationRefs,
            Operation operation,
            String relationshipRef,
            String sourceNodeRef,
            String targetNodeRef,
            Axis axis,
            Kind kind,
            String label,
            String summary,
            Direction direction,
            TemporalScope temporalScope,
            String reason) {}

    @Schema(description = "관계 비교 결과 저장")
    public record Complete(int formatVersion, String contextToken, List<Decision> decisions) {}

    @Schema(description = "핵심 설정 분석과 분리한 관계 분석 실패")
    public record Fail(
            int formatVersion, String sourceHash, String contextToken, FailureCode failureCode) {}

    @Schema(description = "관계도 회차 선택")
    public record EpisodeRef(UUID id, int episodeNo, String title) {}

    @Schema(description = "선택 회차에서 공개된 인물 이름")
    public record Node(UUID id, String name, String description, boolean profileRegistered) {}

    @Schema(description = "선택 회차의 관계")
    public record Edge(
            UUID id,
            UUID sourceNodeId,
            UUID targetNodeId,
            Axis axis,
            Kind kind,
            String label,
            String summary,
            Direction direction,
            EdgeStatus status,
            long version,
            int evidenceCount) {}

    @Schema(description = "회차까지 드러난 관계도. partial은 일부 분석을 완료하지 못했음을 의미합니다.")
    public record Graph(
            EpisodeRef selectedEpisode,
            List<EpisodeRef> availableEpisodes,
            String revision,
            List<Node> nodes,
            List<Edge> edges,
            boolean truncated,
            long totalEdgeCount,
            String analysisStatus,
            boolean partial) {}

    @Schema(description = "관계가 드러난 회차의 원문 근거")
    public record PublicEvidence(
            UUID episodeId,
            int episodeNo,
            String episodeTitle,
            String quote,
            int startOffset,
            int endOffset) {}

    @Schema(description = "선택 회차 기준 관계와 근거")
    public record Detail(
            Edge edge,
            Node sourceNode,
            Node targetNode,
            List<PublicEvidence> evidence,
            long version) {}

    @Schema(description = "잘못 표현된 관계 설명 수정. 서사상의 변화와 구분합니다.")
    public record Correction(long expectedVersion, String summary) {}
}
