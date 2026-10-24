package org.monitoring.catchholebackend.support;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class CandidateReviewApiFixture extends AnalysisProgressApiFixture {
    protected AnalysisJob completedJob(int number) {
        AnalysisJob origin = AnalysisJob.create(work, batch, episode(number), AnalysisJobType.SETTING_EXTRACTION);
        origin.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        complete(origin); em.persist(origin); return origin;
    }

    protected AnalysisJob bookJob() {
        UploadFile book = UploadFile.create(batch, UploadFileRole.SETTING_BOOK, "합성 설정집.txt", "text/plain", "synthetic/book", 10);
        em.persist(book);
        SettingBookRevision revision = SettingBookRevision.create(book, 1,
                new StoredTextObject("synthetic/book", null, "b".repeat(64), 100), SettingBookReferenceTimeKind.EPISODE, 3);
        em.persist(revision); book.linkCurrentRevision(revision);
        AnalysisJob origin = AnalysisJob.createSettingBook(work, book, revision); complete(origin); em.persist(origin); return origin;
    }

    protected void complete(AnalysisJob origin) {
        origin.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode());
        ReflectionTestUtils.setField(origin, "status", AnalysisJobStatus.SUCCEEDED);
        ReflectionTestUtils.setField(origin, "journalStatus", AnalysisJournalStatus.SEALED);
        ReflectionTestUtils.setField(origin, "automaticAppliedAt", LocalDateTime.now());
        ReflectionTestUtils.setField(origin, "inputStateHash", "c".repeat(64));
        ReflectionTestUtils.setField(origin, "stateJournal", JsonNodeFactory.instance.objectNode()
                .put("synthetic", "x".repeat(100_000)).put("outputStateHash", "d".repeat(64))
                .set("changes", JsonNodeFactory.instance.arrayNode()));
        for (String field : java.util.List.of("automaticInputState", "runBaseState"))
            ReflectionTestUtils.setField(origin, field, JsonNodeFactory.instance.objectNode().put("synthetic", "x".repeat(100_000)));
    }

    protected WorldSettingCandidate world(AnalysisJob origin, String subject) {
        var candidate = WorldSettingCandidate.create(work, origin.getEpisode(), origin, WorldSettingCategory.LOCATION,
                subject, "특징", "합성 값", JsonNodeFactory.instance.arrayNode(), new BigDecimal("1.0000"), JsonNodeFactory.instance.objectNode());
        em.persist(candidate); return candidate;
    }

    protected SettingCandidate character(AnalysisJob origin, String name) {
        var candidate = SettingCandidate.create(work, origin.getEpisode(), UUID.randomUUID(), origin,
                SettingEntityType.CHARACTER, name, "나이", "17", SettingValueType.STRING,
                JsonNodeFactory.instance.objectNode().put("value", "17"), JsonNodeFactory.instance.arrayNode(),
                new BigDecimal("1.0000"), JsonNodeFactory.instance.objectNode());
        em.persist(candidate); return candidate;
    }

    protected String worldUrl() { return "/api/v1/works/" + work.getId() + "/world-setting-candidates"; }
    protected String characterUrl() { return "/api/v1/works/" + work.getId() + "/setting-candidates"; }
    protected MockHttpServletRequestBuilder request(String url, UUID source) {
        var request = get(url).header("Authorization", authorization).queryParam("batchId", batch.getId().toString());
        return source == null ? request : request.queryParam("sourceAnalysisJobId", source.toString());
    }
    protected JsonNode read(String url, UUID source) throws Exception {
        return json.readTree(mvc.perform(request(url, source)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
