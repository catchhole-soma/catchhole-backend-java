package org.monitoring.catchholebackend.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.entity.RefreshToken;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.dto.request.MemberWithdrawalCreateRequest;
import org.monitoring.catchholebackend.domain.member.dto.response.MemberWithdrawalResponse;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.entity.MemberWithdrawalRequest;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalDataRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalRequestRepository;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;
import org.monitoring.catchholebackend.domain.member.type.MemberWithdrawalStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeRequestRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.service.WorkPurgeProcessor;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.work.type.WorkPurgeStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "work.purge.worker-drain=0s",
        "member.withdrawal.retry-delay=0s"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("회원 즉시 탈퇴 통합 테스트")
class MemberWithdrawalIntegrationTest {

    private static final String RAW_PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberWithdrawalRequestRepository withdrawalRequestRepository;
    @Autowired private MemberWithdrawalDataRepository withdrawalDataRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private WorkRepository workRepository;
    @Autowired private WorkPurgeRequestRepository workPurgeRequestRepository;
    @Autowired private WorkPurgeDataRepository workPurgeDataRepository;
    @Autowired private MemberWithdrawalService withdrawalService;
    @Autowired private MemberWithdrawalProcessor withdrawalProcessor;
    @Autowired private WorkPurgeProcessor workPurgeProcessor;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ObjectStorage objectStorage;

    private final Set<Long> cleanupMemberIds = new HashSet<>();
    private Member member;
    private String accessToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                create table if not exists episode_chunks (
                    id uuid primary key,
                    episode_id uuid not null
                )
                """);
        reset(objectStorage);
        String unique = UUID.randomUUID().toString().replace("-", "");
        member = memberRepository.save(Member.registerPhoneVerified(
                "withdraw-" + unique + "@example.com",
                passwordEncoder.encode(RAW_PASSWORD),
                "010" + unique.substring(0, 8).replaceAll("[a-f]", "1"),
                "탈퇴 작가"
        ));
        cleanupMemberIds.add(member.getId());
        accessToken = jwtTokenProvider.generateAccessToken(member);
        refreshTokenRepository.save(RefreshToken.builder()
                .member(member)
                .tokenHash(unique + unique)
                .expiresAt(LocalDateTime.now().plusDays(14))
                .build());
    }

    @AfterEach
    void tearDown() {
        for (Long memberId : cleanupMemberIds) {
            withdrawalRequestRepository.findByMemberId(memberId)
                    .ifPresent(withdrawalRequestRepository::delete);
            workPurgeRequestRepository.findAll().stream()
                    .filter(request -> request.getMemberId().equals(memberId))
                    .forEach(workPurgeRequestRepository::delete);
            workRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId)
                    .forEach(work -> workPurgeDataRepository.purgeWorkData(work.getId()));
            withdrawalDataRepository.purgeMemberReferences(memberId, LocalDateTime.now());
            memberRepository.findById(memberId).ifPresent(memberRepository::delete);
        }
        withdrawalRequestRepository.flush();
        workPurgeRequestRepository.flush();
        memberRepository.flush();
        cleanupMemberIds.clear();
    }

    @Test
    @DisplayName("탈퇴 요청 즉시 인증을 차단하고 refresh token 쿠키와 저장 토큰을 폐기한다")
    void withdrawalEndpointImmediatelyBlocksAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "password123",
                                  "confirmation": "회원 탈퇴"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("refreshToken="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth")
                )))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.requestId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));

        assertThat(memberRepository.findById(member.getId())).get()
                .extracting(Member::getStatus)
                .isEqualTo(MemberStatus.PURGING);
        assertThat(refreshTokenRepository.findAllByMemberId(member.getId()))
                .allMatch(RefreshToken::isRevoked);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("확인 문구와 현재 비밀번호가 일치하지 않으면 계정을 변경하지 않는다")
    void withdrawalRejectsInvalidConfirmationAndPassword() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "password123",
                                  "confirmation": "탈퇴"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));

        mockMvc.perform(delete("/api/v1/members/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "wrong-password",
                                  "confirmation": "회원 탈퇴"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MEMBER_WITHDRAWAL_PASSWORD_MISMATCH"));

        assertThat(memberRepository.findById(member.getId())).get()
                .extracting(Member::getStatus)
                .isEqualTo(MemberStatus.ACTIVE);
        assertThat(withdrawalRequestRepository.findByMemberId(member.getId())).isEmpty();
        assertThat(refreshTokenRepository.findAllByMemberId(member.getId()))
                .noneMatch(RefreshToken::isRevoked);
    }

    @Test
    @DisplayName("작품이 없는 회원은 첫 처리에서 회원 행을 hard delete한다")
    void withdrawalWithoutWorksImmediatelyHardDeletesMember() {
        MemberWithdrawalResponse response = withdrawalService.requestWithdrawal(
                member.getId(),
                new MemberWithdrawalCreateRequest(RAW_PASSWORD, "회원 탈퇴")
        );

        withdrawalProcessor.processPendingRequests();

        assertThat(memberRepository.findById(member.getId())).isEmpty();
        MemberWithdrawalRequest completed = withdrawalRequestRepository
                .findById(response.requestId())
                .orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(MemberWithdrawalStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getRetentionExpiresAt()).isAfter(completed.getCompletedAt());
    }

    @Test
    @DisplayName("동시 탈퇴 요청은 회원별 동일 요청 ID로 수렴한다")
    void concurrentWithdrawalRequestsReturnSameRequest() throws Exception {
        MemberWithdrawalCreateRequest request = new MemberWithdrawalCreateRequest(
                RAW_PASSWORD,
                "회원 탈퇴"
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MemberWithdrawalResponse> task = () -> {
            ready.countDown();
            start.await();
            return withdrawalService.requestWithdrawal(member.getId(), request);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MemberWithdrawalResponse> first = executor.submit(task);
            Future<MemberWithdrawalResponse> second = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get().requestId()).isEqualTo(second.get().requestId());
            assertThat(withdrawalRequestRepository.findByMemberId(member.getId())).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("기존 작품 파기가 실패하면 자동 재접수하고 전부 완료된 뒤 회원을 hard delete한다")
    void withdrawalReusesWorkPurgeAndAutomaticallyRetriesFailure() {
        Work firstWork = workRepository.save(Work.create(member, "첫 작품", WorkGenre.FANTASY, null));
        Work secondWork = workRepository.save(Work.create(member, "둘째 작품", WorkGenre.ROMANCE, null));
        MemberWithdrawalCreateRequest request = new MemberWithdrawalCreateRequest(RAW_PASSWORD, "회원 탈퇴");

        MemberWithdrawalResponse firstResponse = withdrawalService.requestWithdrawal(member.getId(), request);
        MemberWithdrawalResponse repeatedResponse = withdrawalService.requestWithdrawal(member.getId(), request);

        assertThat(repeatedResponse.requestId()).isEqualTo(firstResponse.requestId());
        assertThatThrownBy(() -> withdrawalService.requestWithdrawal(
                member.getId(),
                new MemberWithdrawalCreateRequest("wrong-password", "회원 탈퇴")
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(MemberErrorCode.MEMBER_WITHDRAWAL_PASSWORD_MISMATCH));
        withdrawalProcessor.processPendingRequests();
        assertThat(workPurgeRequestRepository.findAll()).filteredOn(purge ->
                        purge.getMemberId().equals(member.getId()))
                .hasSize(2)
                .allMatch(purge -> purge.getStatus() == WorkPurgeStatus.REQUESTED);

        when(objectStorage.purgePrefixes(anyList()))
                .thenReturn(
                        new ObjectStoragePurgeResult(1, 0, 1),
                        new ObjectStoragePurgeResult(0, 0, 0)
                );
        workPurgeProcessor.processPendingRequests();

        assertThat(workRepository.countByMemberId(member.getId())).isEqualTo(1);
        assertThat(workPurgeRequestRepository.findAll()).filteredOn(purge ->
                        purge.getMemberId().equals(member.getId()))
                .extracting(WorkPurgeRequest::getStatus)
                .containsExactlyInAnyOrder(WorkPurgeStatus.FAILED, WorkPurgeStatus.COMPLETED);

        withdrawalProcessor.processPendingRequests();
        assertThat(workPurgeRequestRepository.findAll()).filteredOn(purge ->
                        purge.getMemberId().equals(member.getId()))
                .extracting(WorkPurgeRequest::getStatus)
                .containsExactlyInAnyOrder(WorkPurgeStatus.REQUESTED, WorkPurgeStatus.COMPLETED);
        assertThat(memberRepository.findById(member.getId())).isPresent();

        when(objectStorage.purgePrefixes(anyList()))
                .thenReturn(new ObjectStoragePurgeResult(0, 0, 0));
        workPurgeProcessor.processPendingRequests();
        withdrawalProcessor.processPendingRequests();

        assertThat(workRepository.findById(firstWork.getId())).isEmpty();
        assertThat(workRepository.findById(secondWork.getId())).isEmpty();
        assertThat(memberRepository.findById(member.getId())).isEmpty();
        assertThat(refreshTokenRepository.findAllByMemberId(member.getId())).isEmpty();
        assertThat(workPurgeRequestRepository.findAll()).filteredOn(purge ->
                        purge.getMemberId().equals(member.getId()))
                .hasSize(2)
                .allMatch(purge -> purge.getStatus() == WorkPurgeStatus.COMPLETED);

        MemberWithdrawalRequest completed = withdrawalRequestRepository
                .findById(firstResponse.requestId())
                .orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(MemberWithdrawalStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();

        Member replacement = memberRepository.save(Member.registerPhoneVerified(
                member.getEmail(),
                passwordEncoder.encode(RAW_PASSWORD),
                member.getPhoneNumber(),
                "재가입 작가"
        ));
        cleanupMemberIds.add(replacement.getId());
        assertThat(replacement.getId()).isNotEqualTo(member.getId());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
