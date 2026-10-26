package org.monitoring.catchholebackend.support;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Transactional
public abstract class AnalysisProgressApiFixture {
    @Autowired protected MockMvc mvc;
    @Autowired protected EntityManager em;
    @Autowired protected JwtTokenProvider tokens;
    @Autowired protected ObjectMapper json;
    protected Member member;
    protected Work work;
    protected Work otherWork;
    protected UploadBatch batch;
    protected UploadBatch otherBatch;
    protected String authorization;

    @BeforeEach
    void progressFixture() {
        member = Member.register(UUID.randomUUID() + "@local.invalid", "test-only", "01012345678", "합성 작가");
        em.persist(member);
        Member other = Member.register(UUID.randomUUID() + "@local.invalid", "test-only", "01012345679", "다른 작가");
        em.persist(other);
        work = Work.create(member, "진행 상태 검사", WorkGenre.FANTASY, null); em.persist(work);
        otherWork = Work.create(other, "다른 작품", WorkGenre.FANTASY, null); em.persist(otherWork);
        batch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE); em.persist(batch);
        otherBatch = UploadBatch.create(otherWork, other, UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE); em.persist(otherBatch);
        authorization = "Bearer " + tokens.generateAccessToken(member);
    }

    protected Episode episode(int number) {
        Episode episode = Episode.create(work, null, number, number + "화", "synthetic/" + number, "v1", "a".repeat(64), 100);
        em.persist(episode);
        return episode;
    }

    protected AnalysisJob job(int number) {
        AnalysisJob job = AnalysisJob.create(work, batch, episode(number), AnalysisJobType.SETTING_EXTRACTION);
        em.persist(job);
        return job;
    }

    protected String progressUrl() {
        return "/api/v1/works/" + work.getId() + "/analysis-jobs/progress";
    }

    protected String comparisonUrl() {
        return "/api/v1/works/" + work.getId() + "/world-setting-candidates/progress";
    }
}
