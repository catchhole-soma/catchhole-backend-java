package org.monitoring.catchholebackend.global.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.productusage.service.ProductUsageService;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCreateRequest;
import org.monitoring.catchholebackend.domain.work.service.WorkService;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.config.import=", "product-usage.metrics.enabled=true",
        "product-usage.metrics.fixed-delay-ms=3600000"})
@ActiveProfiles("test") @Transactional
@DisplayName("제품 집계의 명시적 최초 활성화")
class ProductUsageActivationIntegrationTest {
    @Autowired ProductUsageService usage;
    @Autowired ProductUsageSnapshotRepository snapshots;
    @Autowired WorkService works;
    @Autowired EntityManager em;

    @Test @DisplayName("활성화한 앱은 첫 요청 전에 시작 시각을 확정하고 첫 생성부터 기록한다")
    void activatesBeforeFirstWorkCreation() {
        assertThat(usage.isActive()).isTrue();
        LocalDateTime start = snapshots.read(LocalDateTime.now()).startedAt();
        assertThat(start).isNotNull().isBeforeOrEqualTo(LocalDateTime.now());
        Member member = Member.register("activation@example.com", "password", "01011223344", "writer");
        em.persist(member);
        works.createWork(member.getId(), new WorkCreateRequest("first", WorkGenre.FANTASY, null));
        usage.collect(LocalDateTime.now());
        var result = snapshots.read(LocalDateTime.now());
        assertThat(result.startedAt()).isEqualTo(start);
        assertThat(result.months().getLast().worksCreated()).isEqualTo(1);
    }
}
