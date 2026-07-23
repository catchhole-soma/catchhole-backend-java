package org.monitoring.catchholebackend.domain.character.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("캐릭터 설정 Schema Registry Repository 통합 테스트")
class CharacterSettingSchemaRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private CharacterSettingSchemaRepository characterSettingSchemaRepository;

    private Member member;
    private Work work;

    @BeforeEach
    void setUp() {
        member = memberRepository.save(Member.register(
                uniqueEmail("writer"),
                "encoded-password",
                uniquePhoneNumber(),
                "작가"
        ));
        work = workRepository.save(Work.create(member, "게임 속 바바리안 POC", WorkGenre.FANTASY, "schema 검증 작품"));
    }

    @Test
    @DisplayName("JSON alias와 정책 enum을 포함한 schema를 저장하고 조회한다")
    void saveAndFindSchemaWithJsonAndPolicyEnums() {
        JsonNode aliases = aliases("스킬", "skill");
        CharacterSettingSchema schema = characterSettingSchemaRepository.save(CharacterSettingSchema.create(
                null,
                "skills.skill",
                "skill.*",
                "스킬",
                CharacterFactType.SKILL,
                SettingValueType.JSON,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.UPSERT_BY_NAME,
                aliases,
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        ));

        CharacterSettingSchema found = characterSettingSchemaRepository.findById(schema.getId()).orElseThrow();

        assertThat(found.getWork()).isNull();
        assertThat(found.getSchemaKey()).isEqualTo("skills.skill");
        assertThat(found.getAttributePattern()).isEqualTo("skill.*");
        assertThat(found.getFactType()).isEqualTo(CharacterFactType.SKILL);
        assertThat(found.getValueType()).isEqualTo(SettingValueType.JSON);
        assertThat(found.getValueSemantics()).isEqualTo(CharacterSettingValueSemantics.BASE_VALUE);
        assertThat(found.getMergePolicy()).isEqualTo(CharacterSettingMergePolicy.UPSERT_BY_NAME);
        assertThat(found.getAliasesJson()).isEqualTo(aliases);
        assertThat(found.getSource()).isEqualTo(CharacterSettingSchemaSource.SYSTEM_SEED);
        assertThat(found.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("활성 전역 schema와 현재 작품 schema만 schemaKey 순서로 조회한다")
    void findAllActiveForWorkIncludesGlobalAndCurrentWorkOnlyAndSortsBySchemaKey() {
        Work otherWork = workRepository.save(Work.create(member, "다른 작품", WorkGenre.ETC, "다른 schema 범위"));
        characterSettingSchemaRepository.save(schema(
                null,
                "stats.strength",
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        ));
        characterSettingSchemaRepository.save(schema(
                work,
                "stats.agility",
                CharacterSettingSchemaSource.DEV_SEED,
                true
        ));
        characterSettingSchemaRepository.save(schema(
                work,
                "stats.disabled",
                CharacterSettingSchemaSource.DEV_SEED,
                false
        ));
        characterSettingSchemaRepository.save(schema(
                otherWork,
                "stats.other_work",
                CharacterSettingSchemaSource.DEV_SEED,
                true
        ));

        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(work.getId());

        assertThat(schemas)
                .extracting(CharacterSettingSchema::getSchemaKey)
                .containsExactly("stats.agility", "stats.strength");
        assertThat(schemas)
                .extracting(CharacterSettingSchema::getSource)
                .containsExactly(CharacterSettingSchemaSource.DEV_SEED, CharacterSettingSchemaSource.SYSTEM_SEED);
    }

    private CharacterSettingSchema schema(
            Work schemaWork,
            String schemaKey,
            CharacterSettingSchemaSource source,
            boolean enabled
    ) {
        return CharacterSettingSchema.create(
                schemaWork,
                schemaKey,
                null,
                schemaKey,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                aliases(schemaKey),
                source,
                enabled
        );
    }

    private JsonNode aliases(String... values) {
        var aliases = objectMapper.createArrayNode();
        for (String value : values) {
            aliases.add(value);
        }
        return aliases;
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String uniquePhoneNumber() {
        return "010" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
