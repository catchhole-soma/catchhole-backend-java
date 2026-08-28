package org.monitoring.catchholebackend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenAccountRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenExtensionRequestRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenGrantRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.service.AuthService;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineQueryRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodePurgeDataRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.feedback.repository.FeedbackRepository;
import org.monitoring.catchholebackend.domain.legal.repository.LegalDocumentRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalDataRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalRequestRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeRequestRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "work.purge.scheduling-enabled=false",
        "member.withdrawal.scheduling-enabled=false",
        "episode.source-purge.scheduling-enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@DisplayName("애플리케이션 Context 통합 테스트")
class CatchHoleBackendApplicationTests {

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private MemberWithdrawalRequestRepository memberWithdrawalRequestRepository;

    @MockitoBean
    private MemberWithdrawalDataRepository memberWithdrawalDataRepository;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private LegalDocumentRepository legalDocumentRepository;

    @MockitoBean
    private WorkRepository workRepository;

    @MockitoBean
    private EpisodeRepository episodeRepository;

    @MockitoBean
    private EpisodePurgeDataRepository episodePurgeDataRepository;

    @MockitoBean
    private EpisodeSourcePurgeRequestRepository episodeSourcePurgeRequestRepository;

    @MockitoBean
    private UploadBatchRepository uploadBatchRepository;

    @MockitoBean
    private UploadFileRepository uploadFileRepository;

    @MockitoBean
    private WorkPurgeRequestRepository workPurgeRequestRepository;

    @MockitoBean
    private WorkPurgeDataRepository workPurgeDataRepository;

    @MockitoBean
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private PlatformTransactionManager platformTransactionManager;

    @MockitoBean
    private AnalysisJobRepository analysisJobRepository;

    @MockitoBean
    private AiTokenAccountRepository aiTokenAccountRepository;

    @MockitoBean
    private AiTokenExtensionRequestRepository aiTokenExtensionRequestRepository;

    @MockitoBean
    private AiTokenGrantRepository aiTokenGrantRepository;

    @MockitoBean
    private AiTokenUsageRepository aiTokenUsageRepository;

    @MockitoBean
    private FeedbackRepository feedbackRepository;

    @MockitoBean
    private SettingCandidateRepository settingCandidateRepository;

    @MockitoBean
    private WorkCharacterRepository workCharacterRepository;

    @MockitoBean
    private CharacterFactRepository characterFactRepository;

    @MockitoBean
    private CharacterSettingSchemaRepository characterSettingSchemaRepository;

    @MockitoBean
    private CharacterSnapshotSourceRepository characterSnapshotSourceRepository;

    @MockitoBean
    private WorldSettingRepository worldSettingRepository;

    @MockitoBean
    private WorldSettingCandidateRepository worldSettingCandidateRepository;

    @MockitoBean
    private CharacterTimelineQueryRepository characterTimelineQueryRepository;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("Spring ApplicationContext를 로드한다")
    void contextLoads() {
    }

}
