package org.monitoring.catchholebackend.global.config.validation;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.validation.type.ValidationRole;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.Limits;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

/** 운영 예산 예시는 코드 기본값이 아니다. 파일이 완전하고 실제 바인딩 경로로 읽히는지만 확인한다. */
@DisplayName("운영 검수 실행 예산 예시 JSON의 바인딩 (#344)")
class ValidationExecutionProdExampleTest {
    static final Path EXAMPLE = Path.of("deploy/validation-execution.prod.example.json");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Limits SETTING = new Limits(100, 200, 1800, 40000, 20000, 2000, 300, 50, 10);
    static final Limits TYPO = new Limits(20, 20, 900, 20000, 12000, 2000, 100, 50, 10);

    static String exampleJson() throws IOException {
        assertThat(EXAMPLE).as("프로젝트 루트(Gradle 테스트 작업 디렉터리)에 %s가 있어야 한다", EXAMPLE).isRegularFile();
        return Files.readString(EXAMPLE);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flattenedExample() throws IOException {
        var flat = new LinkedHashMap<String, Object>();
        flatten("", JSON.readValue(exampleJson(), Map.class), flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    static void flatten(String prefix, Map<String, Object> node, Map<String, Object> flat) {
        node.forEach((key, value) -> {
            if (value instanceof Map<?, ?> child) flatten(prefix + key + ".", (Map<String, Object>) child, flat);
            else flat.put(prefix + key, value);
        });
    }

    static ValidationExecutionProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("validation.execution", ValidationExecutionProperties.class).get();
    }

    static void assertProductionBudgets(ValidationExecutionProperties properties) {
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.leaseDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.maxLeaseRecoveries()).isEqualTo(2);
        assertThat(properties.setting()).isEqualTo(SETTING);
        assertThat(properties.typo()).isEqualTo(TYPO);
        assertThat(properties.roles()).containsOnlyKeys(ValidationRole.PLANNER, ValidationRole.RELATED_SELECTION,
                ValidationRole.COVERAGE, ValidationRole.JUDGE, ValidationRole.TYPO);
        for (var role : new ValidationRole[] {ValidationRole.PLANNER, ValidationRole.RELATED_SELECTION,
                ValidationRole.COVERAGE, ValidationRole.JUDGE})
            assertThat(properties.roles().get(role)).as(role.name()).isEqualTo(new RoleBudget(40, 2_400_000));
        assertThat(properties.roles().get(ValidationRole.TYPO)).isEqualTo(new RoleBudget(20, 640_000));
        assertThat(properties.roles()).doesNotContainKey(ValidationRole.QUERY_EMBEDDING);
        assertThat(properties.queryEmbeddingModel()).isNull();
    }

    @Test
    @DisplayName("예시 JSON에 실행 스위치만 더하면 모든 운영 예산이 활성 검증을 통과해 바인딩된다")
    void exampleBindsAsEnabledProductionBudgets() throws IOException {
        var properties = flattenedExample();
        assertThat(properties).as("실행 스위치는 VALIDATION_EXECUTION_ENABLED가 소유한다").doesNotContainKey("validation.execution.enabled");
        properties.put("validation.execution.enabled", true);

        assertProductionBudgets(bind(properties));
    }

    @Test
    @DisplayName("스위치 없이 예시 JSON만 있으면 비활성으로 기동한다")
    void exampleWithoutSwitchStaysDisabled() throws IOException {
        assertThat(bind(flattenedExample()).enabled()).isFalse();
    }

    @Test
    @DisplayName("예시에서 TYPO 역할 예산을 빼면 활성 기동이 실패한다")
    void exampleIsCompleteNotMerelyParseable() throws IOException {
        var properties = flattenedExample();
        assertThat(properties.keySet().removeIf(key -> key.startsWith("validation.execution.roles.TYPO."))).isTrue();
        properties.put("validation.execution.enabled", true);

        assertThatThrownBy(() -> bind(properties)).isInstanceOf(BindException.class)
                .rootCause().isInstanceOf(IllegalArgumentException.class).hasMessage("검수 역할 예산이 필요합니다.");
    }

    @Test
    @DisplayName("SPRING_APPLICATION_JSON 한 줄과 VALIDATION_EXECUTION_ENABLED가 application.yml을 거쳐 같은 값으로 바인딩된다")
    void springApplicationJsonPathBindsTheSameBudgets() throws IOException {
        var minified = JSON.writeValueAsString(JSON.readTree(exampleJson()));
        assertThat(minified).doesNotContain("$").doesNotContain("\n");

        assertProductionBudgets(bindLikeContainer(Map.of("VALIDATION_EXECUTION_ENABLED", "true",
                "SPRING_APPLICATION_JSON", minified)));
        assertThatThrownBy(() -> bindLikeContainer(Map.of("VALIDATION_EXECUTION_ENABLED", "true",
                "SPRING_APPLICATION_JSON", "")))
                .as("compose 기본값인 빈 JSON으로 켜면 기동이 실패해야 한다")
                .isInstanceOf(BindException.class)
                .rootCause().hasMessage("검수 실행 제한을 명시적으로 설정해야 합니다.");
        assertThat(bindLikeContainer(Map.of("VALIDATION_EXECUTION_ENABLED", "false", "SPRING_APPLICATION_JSON", ""))
                .enabled()).isFalse();
    }

    /** 컨테이너 환경변수 두 개와 실제 application.yml만으로 Boot의 JSON 후처리와 자리표시자 해석을 거쳐 바인딩한다. */
    static ValidationExecutionProperties bindLikeContainer(Map<String, String> containerEnvironment) throws IOException {
        var environment = new MockEnvironment();
        containerEnvironment.forEach(environment::setProperty);
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        return Binder.get(environment).bindOrCreate("validation.execution", ValidationExecutionProperties.class);
    }
}
