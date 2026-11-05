package org.monitoring.catchholebackend.domain.validation.repository;

import java.util.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationBaselineSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ValidationBaselineSourceRepository extends JpaRepository<ValidationBaselineSource, UUID> {
    List<ValidationBaselineSource> findAllByReportId(UUID reportId);

    /**
     * #381 manifest 행. 설정집 revision 행은 접수 때와 같은 (설정집 id, revisionNo) DB 순서다. 회차 행의 순서는 호출자가
     * 저장된 manifest의 회차 번호로 정한다(이후 회차 번호가 바뀌어도 접수 당시 순서를 지키기 위함).
     */
    @Query("""
            select source from ValidationBaselineSource source
            left join source.settingBookRevision revision
            where source.report.id = :reportId
            order by revision.settingBook.id, revision.revisionNo
            """)
    List<ValidationBaselineSource> findAllByReportIdInSettingBookOrder(@Param("reportId") UUID reportId);

    void deleteAllByReportId(UUID reportId);
}
