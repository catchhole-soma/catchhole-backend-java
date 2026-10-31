package org.monitoring.catchholebackend.global.config.validation;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 컨테이너를 띄우지 않고 docker compose config 렌더 결과만 확인한다. docker compose가 없으면 건너뛴다. */
@DisplayName("운영 API compose가 검수 실행 스위치와 예산 JSON을 그대로 넘긴다 (#344)")
class ValidationExecutionProdComposeTest {
    static final Path COMPOSE = Path.of("deploy/compose.api.prod.yml");
    static final Path EXAMPLE = Path.of("deploy/validation-execution.prod.example.json");
    static final ObjectMapper JSON = new ObjectMapper();
    /** 필수 보간 값만 채운 합성 값이다. 실제 운영 값이 아니다. */
    static final List<String> REQUIRED = List.of(
            "API_DOMAIN=api.example.test",
            "BACKEND_IMAGE=example.test/catchhole-backend:sha-test",
            "BACKEND_JAVA_OPTS=-Xmx256m",
            "DATABASE_URL=jdbc:postgresql://db.example.test:5432/catchhole",
            "DATABASE_USERNAME=synthetic",
            "DATABASE_PASSWORD=synthetic-password",
            "JWT_SECRET=synthetic-jwt-secret-synthetic-jwt-secret",
            "INTERNAL_API_KEY=synthetic-internal-key",
            "CORS_ALLOWED_ORIGINS=https://example.test",
            "AWS_REGION=ap-northeast-2",
            "AWS_S3_BUCKET=synthetic-bucket",
            "REDIS_PASSWORD=synthetic-redis-password");
    /** 셸 환경의 같은 이름 변수가 env-file 값을 덮지 않도록 docker 실행에 필요한 값만 남긴다. */
    static final Set<String> INHERITED = Set.of("PATH", "HOME", "TMPDIR", "DOCKER_HOST", "DOCKER_CONFIG",
            "DOCKER_CONTEXT", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY", "XDG_CONFIG_HOME", "XDG_RUNTIME_DIR");

    @TempDir Path temp;

    record Result(int exitCode, String stdout, String stderr) {}

    static Result run(List<String> command) throws IOException, InterruptedException {
        var stderrFile = Files.createTempFile("gh344-compose-", ".stderr");
        try {
            var builder = new ProcessBuilder(command).redirectError(stderrFile.toFile());
            builder.environment().keySet().removeIf(name -> !INHERITED.contains(name));
            var process = builder.start();
            process.getOutputStream().close();
            var stdout = process.getInputStream().readAllBytes();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("docker compose가 60초 안에 끝나지 않았다");
            }
            return new Result(process.exitValue(), new String(stdout, StandardCharsets.UTF_8),
                    Files.readString(stderrFile));
        } finally {
            Files.deleteIfExists(stderrFile);
        }
    }

    @BeforeAll
    static void requireDockerCompose() throws InterruptedException {
        boolean available;
        try {
            available = run(List.of("docker", "compose", "version")).exitCode() == 0;
        } catch (IOException missingDocker) {
            available = false;
        }
        Assumptions.assumeTrue(available, "docker compose가 없어 렌더 검증을 건너뛴다");
    }

    JsonNode renderBackendEnvironment(List<String> extraLines) throws IOException, InterruptedException {
        var lines = new ArrayList<>(REQUIRED);
        lines.addAll(extraLines);
        var envFile = Files.write(temp.resolve("api.env"), lines, StandardCharsets.UTF_8);
        var result = run(List.of("docker", "compose", "--env-file", envFile.toString(), "-f", COMPOSE.toString(),
                "config", "--format", "json"));
        assertThat(result.exitCode()).as("docker compose config 실패: %s", result.stderr()).isZero();
        return JSON.readTree(result.stdout()).path("services").path("backend").path("environment");
    }

    @Test
    @DisplayName("api.env의 한 줄 JSON과 스위치가 byte 그대로 backend 환경변수로 렌더된다")
    void rendersJsonAndSwitchVerbatim() throws Exception {
        var minified = JSON.writeValueAsString(JSON.readTree(Files.readString(EXAMPLE)));

        var environment = renderBackendEnvironment(List.of("VALIDATION_EXECUTION_ENABLED=true",
                "SPRING_APPLICATION_JSON=" + minified));

        assertThat(environment.path("SPRING_APPLICATION_JSON").asString()).isEqualTo(minified);
        assertThat(environment.path("VALIDATION_EXECUTION_ENABLED").asString()).isEqualTo("true");
    }

    @Test
    @DisplayName("두 값이 api.env에 없으면 빈 JSON과 false로 렌더된다")
    void rendersDisabledDefaultsWhenKeysAreAbsent() throws Exception {
        var environment = renderBackendEnvironment(List.of());

        assertThat(environment.path("SPRING_APPLICATION_JSON").isString()).isTrue();
        assertThat(environment.path("SPRING_APPLICATION_JSON").asString()).isEmpty();
        assertThat(environment.path("VALIDATION_EXECUTION_ENABLED").asString()).isEqualTo("false");
    }
}
