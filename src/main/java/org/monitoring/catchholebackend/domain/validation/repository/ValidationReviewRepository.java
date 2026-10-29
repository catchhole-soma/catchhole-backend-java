package org.monitoring.catchholebackend.domain.validation.repository;
import jakarta.persistence.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Repository;
/** 작가 검토 조회. 현재 항목은 공개 results와 같은 규칙으로 현재 check revision의 finding과 TYPO finding만이다. */
@Repository @RequiredArgsConstructor
public class ValidationReviewRepository {
    private final EntityManager em;
    public record FindingRow(ValidationFinding finding,ValidationJudgment judgment) {}
    public record JudgmentCount(ValidationBranch branch,ValidationJudgmentValue judgment,long count) {}
    private static final String CURRENT=" from ValidationFinding f left join ValidationJudgment j on j.finding=f where f.report.id=:r"
            +" and (f.checkId is null or exists (select c.id from ValidationCheck c where c.id=f.checkId and c.revision=f.checkRevision))";
    public Page<FindingRow> currentFindings(UUID r,ValidationBranch branch,ValidationJudgmentValue judgment,String outcome,Pageable page) {
        String where=CURRENT+(branch==null?"":" and f.branch=:branch")+(outcome==null?"":" and f.outcome=:outcome")
                +(judgment==null?"":judgment==ValidationJudgmentValue.PENDING?" and (j.id is null or j.judgment=:judgment)":" and j.judgment=:judgment");
        var rows=bind(em.createQuery("select f,j"+where+" order by f.createdAt,f.id",Object[].class),r,branch,judgment,outcome).setFirstResult((int)page.getOffset()).setMaxResults(page.getPageSize()).getResultList();
        long total=bind(em.createQuery("select count(f)"+where,Long.class),r,branch,judgment,outcome).getSingleResult();
        return new PageImpl<>(rows.stream().map(x->new FindingRow((ValidationFinding)x[0],(ValidationJudgment)x[1])).toList(),page,total);
    }
    /** 판단 행이 없는 현재 항목은 PENDING으로 센다. */
    public List<JudgmentCount> currentJudgmentCounts(UUID r) {
        return em.createQuery("select f.branch,j.judgment,count(f)"+CURRENT+" group by f.branch,j.judgment",Object[].class).setParameter("r",r).getResultList().stream()
                .map(x->new JudgmentCount((ValidationBranch)x[0],x[1]==null?ValidationJudgmentValue.PENDING:(ValidationJudgmentValue)x[1],(Long)x[2])).toList();
    }
    public Set<UUID> revisedCheckIds(UUID r) { return new HashSet<>(em.createQuery("select distinct h.check.id from ValidationCheckHistory h where h.report.id=:r",UUID.class).setParameter("r",r).getResultList()); }
    public Optional<ValidationFinding> finding(UUID r,UUID id) { return em.createQuery("select f from ValidationFinding f where f.id=:id and f.report.id=:r",ValidationFinding.class).setParameter("id",id).setParameter("r",r).getResultStream().findFirst(); }
    public Optional<Long> checkRevision(UUID check) { return em.createQuery("select c.revision from ValidationCheck c where c.id=:c",Long.class).setParameter("c",check).getResultStream().findFirst(); }
    public List<ValidationCheckHistory> checkHistory(UUID check) { return em.createQuery("select h from ValidationCheckHistory h where h.check.id=:c order by h.revision",ValidationCheckHistory.class).setParameter("c",check).getResultList(); }
    public List<ValidationFinding> checkFindings(UUID check) { return em.createQuery("select f from ValidationFinding f where f.checkId=:c order by f.checkRevision,f.createdAt,f.id",ValidationFinding.class).setParameter("c",check).getResultList(); }
    /** release Job의 상태만 읽는다. 큰 JSON 입력/journal 컬럼은 읽지 않는다. */
    public Optional<Object[]> extractionJobState(UUID job) { return em.createQuery("select j.status,j.journalStatus from AnalysisJob j where j.id=:j",Object[].class).setParameter("j",job).getResultStream().findFirst(); }
    public List<ValidationEvidence> evidence(UUID r,String owner) { return em.createQuery("select e from ValidationEvidence e where e.report.id=:r and e.ownerRef=:o order by e.createdAt,e.id",ValidationEvidence.class).setParameter("r",r).setParameter("o",owner).getResultList(); }
    private <T> TypedQuery<T> bind(TypedQuery<T> q,UUID r,ValidationBranch branch,ValidationJudgmentValue judgment,String outcome) {
        q.setParameter("r",r);if(branch!=null)q.setParameter("branch",branch);if(judgment!=null)q.setParameter("judgment",judgment);if(outcome!=null)q.setParameter("outcome",outcome);return q;
    }
}
