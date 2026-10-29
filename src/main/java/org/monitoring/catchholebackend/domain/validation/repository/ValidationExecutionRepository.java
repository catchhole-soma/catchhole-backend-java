package org.monitoring.catchholebackend.domain.validation.repository;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.springframework.stereotype.Repository;
/** Mutations are owned by the short Work -> Job transaction. */
@Repository @RequiredArgsConstructor
public class ValidationExecutionRepository {
    private final EntityManager em;
    public void save(Object entity) { em.persist(entity); }
    public List<ValidationStage> stages(UUID r) { return em.createQuery("select x from ValidationStage x where x.report.id=:r order by x.branch,x.stage,x.ref",ValidationStage.class).setParameter("r",r).getResultList(); }
    public List<ValidationCheck> checks(UUID r) { return em.createQuery("select x from ValidationCheck x where x.report.id=:r order by x.localRef",ValidationCheck.class).setParameter("r",r).getResultList(); }
    public Optional<ValidationPublication> publication(UUID r,String key) { return em.createQuery("select x from ValidationPublication x where x.report.id=:r and x.requestKey=:key",ValidationPublication.class).setParameter("r",r).setParameter("key",key).getResultStream().findFirst(); }
    public Optional<ValidationCheckpoint> checkpoint(UUID r,ValidationBranch b) { return em.createQuery("select x from ValidationCheckpoint x where x.report.id=:r and x.branch=:b",ValidationCheckpoint.class).setParameter("r",r).setParameter("b",b).getResultStream().findFirst(); }
    public List<ValidationFinding> findings(UUID r) { return em.createQuery("select x from ValidationFinding x where x.report.id=:r order by x.createdAt,x.id",ValidationFinding.class).setParameter("r",r).getResultList(); }
    public List<ValidationEvidence> evidence(UUID r) { return em.createQuery("select x from ValidationEvidence x where x.report.id=:r order by x.createdAt,x.id",ValidationEvidence.class).setParameter("r",r).getResultList(); }
    public boolean hasSuccessfulVectorReceipt(UUID report) {
        for(var c:checks(report))if(vector(c.getDecision()))return true;
        for(var h:em.createQuery("select h from ValidationCheckHistory h where h.report.id=:r",ValidationCheckHistory.class).setParameter("r",report).getResultList())if(vector(h.getDecision()))return true;
        return false;
    }
    private boolean vector(com.fasterxml.jackson.databind.JsonNode decision) {if(decision==null)return false;for(var r:decision.path("evidence_bundle").path("receipts"))if("SUCCEEDED".equals(r.path("status").asText())&&"VECTOR".equals(r.path("intent").path("operation").asText()))return true;return false;}
    public void purge(UUID r) {
        for(String entity:List.of("ValidationJudgmentHistory","ValidationJudgment","ValidationCheckHistory","ValidationEvidence","ValidationFinding","ValidationCheckpoint","ValidationPublication","ValidationCheck","ValidationStage"))
            em.createQuery("delete from "+entity+" x where x.report.id=:r").setParameter("r",r).executeUpdate();
    }
}
