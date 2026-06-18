package org.monitoring.catchholebackend.domain.character.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkCharacterRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private WorkCharacterRepository workCharacterRepository;

    private Work work;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.save(Member.register(
                uniqueEmail("writer"),
                "encoded-password",
                uniquePhoneNumber(),
                "작가"
        ));
        work = workRepository.save(Work.create(member, "은빛 검사", "판타지", "검사 성장물"));
    }

    @Test
    void saveAndFindCharacterWithJsonSettings() {
        UUID firstAppearanceEpisodeId = UUID.randomUUID();
        JsonNode profileJson = objectMapper.createObjectNode()
                .put("gender", "female")
                .put("species", "human")
                .put("affiliation", "북부 기사단")
                .put("description", "북부 기사단 소속 검사");
        JsonNode statsJson = objectMapper.createObjectNode()
                .set("strength", objectMapper.createObjectNode()
                        .put("display_name", "근력")
                        .put("value", 42)
                        .put("unit", "points"));
        JsonNode skillsJson = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("name", "은월참")
                        .put("category", "combat")
                        .put("level", 3)
                        .put("effect", "달빛 속성의 검격"));
        JsonNode itemsJson = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("name", "푸른 마나석")
                        .put("category", "consumable")
                        .put("quantity", 2)
                        .put("equipped", false));
        JsonNode statusesJson = objectMapper.createObjectNode()
                .set("time_state", objectMapper.createObjectNode()
                        .put("current_episode_time", "왕국력 312년 초여름 밤")
                        .put("narrative_context", "PRESENT"));

        WorkCharacter character = workCharacterRepository.save(WorkCharacter.create(
                work,
                "아리아",
                "protagonist",
                17,
                23,
                profileJson,
                statsJson,
                skillsJson,
                itemsJson,
                statusesJson,
                firstAppearanceEpisodeId
        ));

        WorkCharacter found = workCharacterRepository.findByWorkIdAndName(work.getId(), "아리아").orElseThrow();

        assertThat(found.getId()).isEqualTo(character.getId());
        assertThat(found.getName()).isEqualTo("아리아");
        assertThat(found.getRoleLabel()).isEqualTo("protagonist");
        assertThat(found.getCurrentAge()).isEqualTo(17);
        assertThat(found.getCurrentLevel()).isEqualTo(23);
        assertThat(found.getFirstAppearanceEpisodeId()).isEqualTo(firstAppearanceEpisodeId);
        assertThat(found.getReviewStatus()).isEqualTo(CharacterReviewStatus.PENDING_REVIEW);
        assertThat(found.getStatus()).isEqualTo(CharacterStatus.ACTIVE);
        assertThat(found.getProfileJson()).isEqualTo(profileJson);
        assertThat(found.getStatsJson()).isEqualTo(statsJson);
        assertThat(found.getSkillsJson()).isEqualTo(skillsJson);
        assertThat(found.getItemsJson()).isEqualTo(itemsJson);
        assertThat(found.getStatusesJson()).isEqualTo(statusesJson);
    }

    @Test
    void findAllByWorkIdReturnsOnlyWorkCharacters() {
        Member otherMember = memberRepository.save(Member.register(
                uniqueEmail("other"),
                "encoded-password",
                uniquePhoneNumber(),
                "다른 작가"
        ));
        Work otherWork = workRepository.save(Work.create(otherMember, "다른 작품", "무협", "다른 설명"));

        workCharacterRepository.save(WorkCharacter.create(
                work, "아리아", "protagonist", 17, 23, null, null, null, null, null, null
        ));
        workCharacterRepository.save(WorkCharacter.create(
                otherWork, "세이라", "supporter", null, null, null, null, null, null, null, null
        ));

        List<WorkCharacter> characters = workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId());

        assertThat(characters).hasSize(1);
        assertThat(characters.getFirst().getName()).isEqualTo("아리아");
    }

    @Test
    void confirmDismissAndArchiveUpdateCharacterStatuses() {
        WorkCharacter confirmed = workCharacterRepository.save(WorkCharacter.create(
                work, "아리아", "protagonist", 17, 23, null, null, null, null, null, null
        ));
        WorkCharacter dismissed = workCharacterRepository.save(WorkCharacter.create(
                work, "세이라", "supporter", null, null, null, null, null, null, null, null
        ));

        confirmed.confirm();
        dismissed.dismiss();
        dismissed.archive();

        assertThat(confirmed.getReviewStatus()).isEqualTo(CharacterReviewStatus.CONFIRMED);
        assertThat(confirmed.getStatus()).isEqualTo(CharacterStatus.ACTIVE);
        assertThat(dismissed.getReviewStatus()).isEqualTo(CharacterReviewStatus.DISMISSED);
        assertThat(dismissed.getStatus()).isEqualTo(CharacterStatus.ARCHIVED);
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String uniquePhoneNumber() {
        return "010" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
