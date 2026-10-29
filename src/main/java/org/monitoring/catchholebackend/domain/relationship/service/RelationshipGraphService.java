package org.monitoring.catchholebackend.domain.relationship.service;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Complete;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Context;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Correction;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Detail;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Fail;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Graph;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Prepare;

import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;

import java.util.Optional;
import java.util.UUID;

public interface RelationshipGraphService {
    void beginRelationshipAnalysis(AnalysisJob job);

    Optional<Context> getRelationshipAnalysis(UUID jobId, UUID lease);

    Context prepareRelationshipAnalysis(UUID jobId, UUID lease, Prepare request);

    void completeRelationshipAnalysis(UUID jobId, UUID lease, Complete request);

    void failRelationshipAnalysis(UUID jobId, UUID lease, Fail request);

    void finalizeRelationshipAnalysis(AnalysisJob job);

    Graph getRelationshipGraph(Long memberId, UUID workId, Integer episodeNo);

    Detail getRelationshipEdge(Long memberId, UUID workId, UUID edgeId, Integer episodeNo);

    void correctRelationshipEdge(Long memberId, UUID workId, UUID edgeId, Correction request);

    void deleteRelationshipEdge(Long memberId, UUID workId, UUID edgeId, long expectedVersion);
}
