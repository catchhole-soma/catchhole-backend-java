package org.monitoring.catchholebackend.domain.work.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member member;
    private Member otherMember;
    private String accessToken;

    @BeforeEach
    void setUp() {
        workRepository.deleteAll();
        memberRepository.deleteAll();

        member = memberRepository.save(Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        ));
        otherMember = memberRepository.save(Member.register(
                "other@example.com",
                "encoded-password",
                "01087654321",
                "다른 작가"
        ));
        accessToken = jwtTokenProvider.generateAccessToken(member);
    }

    @Test
    void createWorkCreatesActiveWorkForAuthenticatedMember() throws Exception {
        mockMvc.perform(post("/api/v1/works")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "빛나는 검사 로맨스",
                                  "genre": "로맨스",
                                  "description": "검사 주인공의 성장과 로맨스"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("작품이 등록되었습니다."))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.title").value("빛나는 검사 로맨스"))
                .andExpect(jsonPath("$.data.genre").value("로맨스"))
                .andExpect(jsonPath("$.data.description").value("검사 주인공의 성장과 로맨스"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.latestEpisodeNo").value(0));
    }

    @Test
    void getMyWorksReturnsOnlyAuthenticatedMemberWorks() throws Exception {
        workRepository.save(Work.create(member, "첫 번째 작품", "로맨스", "첫 번째 설명"));
        Thread.sleep(10);
        workRepository.save(Work.create(member, "두 번째 작품", "무협", "두 번째 설명"));
        workRepository.save(Work.create(otherMember, "다른 회원 작품", "판타지", "다른 설명"));

        mockMvc.perform(get("/api/v1/works")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].title").value("두 번째 작품"))
                .andExpect(jsonPath("$.data[1].title").value("첫 번째 작품"));
    }

    @Test
    void updateWorkUpdatesAuthenticatedMembersWork() throws Exception {
        Work work = workRepository.save(Work.create(member, "수정 전", "로맨스", "수정 전 설명"));

        mockMvc.perform(patch("/api/v1/works/{workId}", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "수정 후",
                                  "genre": "판타지",
                                  "description": "수정 후 설명"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("작품이 수정되었습니다."))
                .andExpect(jsonPath("$.data.id").value(work.getId().toString()))
                .andExpect(jsonPath("$.data.title").value("수정 후"))
                .andExpect(jsonPath("$.data.genre").value("판타지"))
                .andExpect(jsonPath("$.data.description").value("수정 후 설명"));
    }

    @Test
    void updateWorkRejectsOtherMemberWork() throws Exception {
        Work otherWork = workRepository.save(Work.create(otherMember, "다른 회원 작품", "무협", "다른 설명"));

        mockMvc.perform(patch("/api/v1/works/{workId}", otherWork.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "수정 시도",
                                  "genre": "로맨스",
                                  "description": "수정 시도 설명"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    @Test
    void getMyWorksRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/works"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
