package org.monitoring.catchholebackend;

import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.service.AuthService;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
class CatchHoleBackendApplicationTests {

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private WorkRepository workRepository;

    @MockitoBean
    private EpisodeRepository episodeRepository;

    @MockitoBean
    private UploadBatchRepository uploadBatchRepository;

    @MockitoBean
    private UploadFileRepository uploadFileRepository;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void contextLoads() {
    }

}
