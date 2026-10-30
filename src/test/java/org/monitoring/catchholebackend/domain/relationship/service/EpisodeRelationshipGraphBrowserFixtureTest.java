package org.monitoring.catchholebackend.domain.relationship.service;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Axis;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Context;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EdgeStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Kind;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphAnalysis;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphNode;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphRules;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in synthetic browser fixture. Only the dedicated disposable local database is permitted. */
@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:18232/gh323_browser",
            "spring.datasource.username=gh323",
            "spring.datasource.password=test-only",
            "spring.datasource.driver-class-name=org.postgresql.Driver",
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.docker.compose.enabled=false",
            "storage.local.root=/tmp/gh323-browser-storage",
            "relationship.processing.scheduling-enabled=false"
        })
@ActiveProfiles("e2e")
@EnabledIfEnvironmentVariable(
        named = "GH323_BROWSER_FIXTURE_OUTPUT",
        matches = "/tmp/gh323-relationship-browser.json")
class EpisodeRelationshipGraphBrowserFixtureTest {
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JwtTokenProvider jwt;
    @Autowired ObjectStorageService storage;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void createDisposableFixture() throws Exception {
        Map<String, Object> credentials =
                tx.execute(
                        status -> {
                            Member member =
                                    Member.register(
                                            "graph-" + UUID.randomUUID() + "@example.test",
                                            "unused-synthetic-password",
                                            null,
                                            "관계도 검증 작가");
                            em.persist(member);
                            Work work =
                                    Work.create(
                                            member,
                                            "GH323 회차별 관계도 검증",
                                            WorkGenre.FANTASY,
                                            "격리 DB 합성 자료");
                            em.persist(work);
                            String firstText = "아라와 보라는 함께 길을 떠났다.";
                            String secondText = "아라는 보라와 헤어지고 보라를 경계했다. 세린은 아라의 친구다.";
                            Episode first = episode(work, 1, firstText),
                                    second = episode(work, 2, secondText);
                            RelationshipGraphAnalysis a = analysis(work, first),
                                    b = analysis(work, second);
                            RelationshipGraphNode ara = node(work, "아라", 1),
                                    bora = node(work, "보라", 1),
                                    serin = node(work, "세린", 2);
                            RelationshipGraphEdge companionship =
                                    edge(
                                            work,
                                            ara,
                                            bora,
                                            Axis.SOCIAL,
                                            Kind.COMPANIONSHIP,
                                            Direction.MUTUAL);
                            RelationshipGraphEdge distrust =
                                    edge(
                                            work,
                                            ara,
                                            bora,
                                            Axis.ATTITUDE,
                                            Kind.DISTRUST,
                                            Direction.DIRECTED);
                            RelationshipGraphEdge friendship =
                                    edge(
                                            work,
                                            ara,
                                            serin,
                                            Axis.SOCIAL,
                                            Kind.FRIENDSHIP,
                                            Direction.MUTUAL);
                            event(
                                    a,
                                    companionship,
                                    ara,
                                    bora,
                                    Direction.MUTUAL,
                                    EdgeStatus.ACTIVE,
                                    "동행",
                                    "같이 여행하는 사이",
                                    firstText);
                            event(
                                    b,
                                    companionship,
                                    ara,
                                    bora,
                                    Direction.MUTUAL,
                                    EdgeStatus.ENDED,
                                    "동행",
                                    "함께하던 여행을 끝냈다",
                                    secondText);
                            event(
                                    b,
                                    distrust,
                                    ara,
                                    bora,
                                    Direction.DIRECTED,
                                    EdgeStatus.ACTIVE,
                                    "경계",
                                    "아라가 보라를 경계한다",
                                    secondText);
                            event(
                                    b,
                                    friendship,
                                    ara,
                                    serin,
                                    Direction.MUTUAL,
                                    EdgeStatus.ACTIVE,
                                    "친구",
                                    "아라와 세린은 친구다",
                                    secondText);
                            return Map.of(
                                    "workId",
                                    work.getId(),
                                    "memberId",
                                    member.getId(),
                                    "accessToken",
                                    jwt.generateAccessToken(member),
                                    "deleteEdgeId",
                                    friendship.getId(),
                                    "correctEdgeId",
                                    distrust.getId(),
                                    "apiBaseUrl",
                                    "http://localhost:18233");
                        });
        Path path = Path.of(System.getenv("GH323_BROWSER_FIXTURE_OUTPUT"));
        Files.writeString(path, json.writeValueAsString(credentials));
        Files.setPosixFilePermissions(
                path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    private Episode episode(Work work, int no, String text) {
        Episode episode =
                Episode.create(
                        work,
                        null,
                        no,
                        no + "화",
                        "synthetic/" + UUID.randomUUID(),
                        "v1",
                        storage.contentHash(text),
                        text.length());
        em.persist(episode);
        return episode;
    }

    private RelationshipGraphAnalysis analysis(Work work, Episode episode) {
        AnalysisJob job =
                AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        job.enableRelationshipAnalysis();
        em.persist(job);
        Context context =
                new Context(
                        1,
                        episode.getContentHash(),
                        "synthetic",
                        AnalysisStatus.PREPARED,
                        episode.getEpisodeNo(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        RelationshipGraphAnalysis analysis =
                RelationshipGraphAnalysis.prepare(
                        job.getId(),
                        work.getId(),
                        episode.getId(),
                        episode.getEpisodeNo(),
                        episode.getContentHash(),
                        "synthetic",
                        "synthetic",
                        json.valueToTree(context),
                        json.createObjectNode(),
                        0);
        analysis.complete(json.createArrayNode(), "synthetic");
        analysis.apply();
        em.persist(analysis);
        return analysis;
    }

    private RelationshipGraphNode node(Work work, String name, int no) {
        var node = RelationshipGraphNode.create(UUID.randomUUID(), work.getId(), null, name, no);
        em.persist(node);
        return node;
    }

    private RelationshipGraphEdge edge(
            Work work,
            RelationshipGraphNode source,
            RelationshipGraphNode target,
            Axis axis,
            Kind kind,
            Direction direction) {
        var edge =
                RelationshipGraphEdge.create(
                        work.getId(),
                        source.getId(),
                        target.getId(),
                        axis,
                        kind,
                        storage.contentHash(
                                RelationshipGraphRules.canonical(
                                        source.getId().toString(),
                                        target.getId().toString(),
                                        axis,
                                        kind,
                                        direction)));
        em.persist(edge);
        return edge;
    }

    private void event(
            RelationshipGraphAnalysis analysis,
            RelationshipGraphEdge edge,
            RelationshipGraphNode source,
            RelationshipGraphNode target,
            Direction direction,
            EdgeStatus status,
            String label,
            String summary,
            String quote) {
        var evidence =
                json.valueToTree(
                        List.of(
                                Map.of(
                                        "episodeId",
                                        analysis.getEpisodeId(),
                                        "episodeNo",
                                        analysis.getEpisodeNo(),
                                        "quote",
                                        quote,
                                        "startOffset",
                                        0,
                                        "endOffset",
                                        quote.codePointCount(0, quote.length()))));
        em.persist(
                RelationshipGraphEvent.create(
                        analysis.getWorkId(),
                        analysis.getId(),
                        edge.getId(),
                        analysis.getEpisodeId(),
                        analysis.getEpisodeNo(),
                        source.getName(),
                        target.getName(),
                        label,
                        summary,
                        direction,
                        status,
                        evidence));
        edge.changed();
    }
}
