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
    /**
     * GH349: 이전 grid 빌더로 만든 배포 전 미완료 run의 SETTING 분기 사본을 지운다. 재검사가 다시 만들므로 SETTING finding과 그 작가
     * 판단·판단 이력도 지운다. FK 순서: 판단 이력 → 판단 → finding → SETTING plan·checkpoint receipt → TYPO 밖의 인용 사본 →
     * check 이력 → check → SETTING checkpoint → SETTING stage. receipt는 같은 key·본문 재전송이 새 stage/checkpoint 대신 옛 receipt를
     * 돌려주지 않게 지운다. TYPO 분기 전부(finding·판단·stage·checkpoint·receipt), check 판정 unit·revision·완료 receipt,
     * 정정 receipt와 사용량 원장은 남긴다.
     */
    public void discardSettingBranch(UUID r) {
        em.createQuery("delete from ValidationJudgmentHistory x where x.report.id=:r and x.finding.id in (select f.id from ValidationFinding f where f.report.id=:r and f.branch=:b)")
                .setParameter("r",r).setParameter("b",ValidationBranch.SETTING).executeUpdate();
        for(String entity:List.of("ValidationJudgment","ValidationFinding"))
            em.createQuery("delete from "+entity+" x where x.report.id=:r and x.branch=:b").setParameter("r",r).setParameter("b",ValidationBranch.SETTING).executeUpdate();
        var receipts=new ArrayList<UUID>();
        for(var p:em.createQuery("select x from ValidationPublication x where x.report.id=:r",ValidationPublication.class).setParameter("r",r).getResultList())
            if("SETTING".equals(p.getResponse().path("branch").asText()))receipts.add(p.getId());
        if(!receipts.isEmpty())em.createQuery("delete from ValidationPublication x where x.id in :ids").setParameter("ids",receipts).executeUpdate();
        em.createQuery("delete from ValidationEvidence x where x.report.id=:r and x.ownerRef not like 'TYPO:%' and x.ownerRef<>'CHECKPOINT:TYPO'").setParameter("r",r).executeUpdate();
        em.createQuery("delete from ValidationCheckHistory x where x.report.id=:r").setParameter("r",r).executeUpdate();
        em.createQuery("delete from ValidationCheck x where x.report.id=:r").setParameter("r",r).executeUpdate();
        for(String entity:List.of("ValidationCheckpoint","ValidationStage"))
            em.createQuery("delete from "+entity+" x where x.report.id=:r and x.branch=:b").setParameter("r",r).setParameter("b",ValidationBranch.SETTING).executeUpdate();
    }
    /** report 복사본을 지운다. 검토 종료 receipt는 무효화·퇴역 뒤에도 이력으로 남기며 작품/회원 파기만 지운다(GH312). */
    public void purge(UUID r) {
        for(String entity:List.of("ValidationJudgmentHistory","ValidationJudgment","ValidationCheckHistory","ValidationEvidence","ValidationFinding","ValidationCheckpoint","ValidationPublication","ValidationCheck","ValidationStage"))
            em.createQuery("delete from "+entity+" x where x.report.id=:r").setParameter("r",r).executeUpdate();
    }
}
