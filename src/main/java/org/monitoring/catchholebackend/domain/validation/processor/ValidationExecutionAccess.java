package org.monitoring.catchholebackend.domain.validation.processor;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.Scope;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationJobRepository;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ValidationExecutionAccess {
    private final ValidationJobRepository jobs;
    private final WorkRepository works;
    private final EntityManager em;
    public ValidationJob lock(UUID id) {
        UUID work=jobs.findWorkId(id).orElseThrow(()->error(ValidationErrorCode.VALIDATION_NOT_FOUND));
        works.findByIdForUpdate(work).orElseThrow(()->error(ValidationErrorCode.VALIDATION_NOT_FOUND));
        var job=jobs.findForUpdate(id).orElseThrow(()->error(ValidationErrorCode.VALIDATION_NOT_FOUND));
        em.refresh(job);em.refresh(job.getReport());return job;
    }
    public ValidationJob running(UUID id,UUID token,Scope scope) { var j=lock(id);require(j,token,scope);return j; }
    public void require(ValidationJob j,UUID token,Scope scope) {
        if(scope==null||scope.contractVersion()!=1||!j.getReport().getId().equals(scope.reportId())||j.getGeneration()!=scope.generation()
                ||!j.getInputDigest().equals(scope.inputDigest())||!j.runningLease(token))throw error(ValidationErrorCode.VALIDATION_LEASE_CONFLICT);
        current(j.getReport());
    }
    public void current(ValidationReport r) { currentInput(r,true); }
    public void currentInput(ValidationReport r,boolean readyRequired) {
        // Scalar projection cannot reuse a stale Episode or report from the persistence context.
        var row=em.createQuery("select r.current,cast(r.baselineStatus as string),cast(r.work.lifecycleStatus as string),cast(r.member.status as string),r.sourceContentRevision,r.episode.contentRevision,r.sourceContentHash,r.episode.contentHash,r.sourceContentS3Key,r.episode.contentS3Key,r.sourceContentS3Version,r.episode.contentS3Version,cast(r.episode.status as string) from ValidationReport r where r.id=:r",Object[].class).setParameter("r",r.getId()).getSingleResult();
        if(!Boolean.TRUE.equals(row[0])||"INVALIDATED".equals(row[1])||readyRequired&&!"READY".equals(row[1])||!"ACTIVE".equals(row[2])||!"ACTIVE".equals(row[3])
                ||!Objects.equals(row[4],row[5])||!Objects.equals(row[6],row[7])||!Objects.equals(row[8],row[9])||!Objects.equals(row[10],row[11])||"ARCHIVED".equals(row[12]))
            throw error(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
    }
    public void requireRead(UUID reportId,UUID jobId,UUID token) {
        var rows=em.createQuery("select j.id,j.leaseToken,j.leaseExpiresAt,cast(j.status as string) from ValidationJob j where j.report.id=:r",Object[].class).setParameter("r",reportId).getResultList();
        if(rows.isEmpty()) { if(jobId!=null||token!=null)throw error(ValidationErrorCode.VALIDATION_LEASE_CONFLICT);return; }
        var row=rows.getFirst();
        if(jobId==null||token==null||!jobId.equals(row[0])||!token.equals(row[1])||!"RUNNING".equals(row[3])||!java.time.Instant.now().isBefore((java.time.Instant)row[2]))
            throw error(ValidationErrorCode.VALIDATION_LEASE_CONFLICT);
    }
    public static AppException error(ValidationErrorCode code) { return new AppException(code); }
}
