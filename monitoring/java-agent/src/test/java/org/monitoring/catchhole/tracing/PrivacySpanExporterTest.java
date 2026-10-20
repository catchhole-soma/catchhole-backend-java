package org.monitoring.catchhole.tracing;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Java Agent 전송 전 개인정보 제거")
class PrivacySpanExporterTest {
    private static final String SECRET = "CANARY-secret-원문-토큰";
    private static final SpanContext REMOTE = SpanContext.createFromRemoteParent(
            "11111111111111111111111111111111", "2222222222222222", TraceFlags.getSampled(),
            TraceState.builder().put("untrusted", "private-value").build());

    @Test
    @DisplayName("SQL·URL·예외·자유 속성을 제거하면서 부모 관계와 시간·상태를 보존한다")
    void sanitizesBeforeDelegateExport() {
        CapturingExporter sink = new CapturingExporter();
        try (SdkTracerProvider provider = provider(sink)) {
            Span span = provider.get("untrusted-" + SECRET).spanBuilder(SECRET)
                    .setSpanKind(SpanKind.SERVER)
                    .setParent(Context.root().with(Span.wrap(REMOTE)))
                    .setAttribute("http.request.method", "POST")
                    .setAttribute("http.route", "/api/internal/v1/ai-token-usages/reserve")
                    .setAttribute("http.response.status_code", 500)
                    .setAttribute("url.full", "http://localhost/?secret=" + SECRET)
                    .setAttribute("db.statement", "SELECT '" + SECRET + "'")
                    .setAttribute("http.request.header.authorization", SECRET)
                    .setAttribute("unknown", SECRET)
                    .addLink(REMOTE, Attributes.of(AttributeKey.stringKey("unsafe"), SECRET))
                    .startSpan();
            span.recordException(new IllegalStateException(SECRET));
            span.addEvent(SECRET, Attributes.of(AttributeKey.stringKey("body"), SECRET));
            span.setStatus(StatusCode.ERROR, SECRET);
            String expectedSpanId = span.getSpanContext().getSpanId();
            span.end();

            SpanData result = sink.spans.getFirst();
            assertEquals(REMOTE.getTraceId(), result.getTraceId());
            assertEquals(REMOTE.getSpanId(), result.getParentSpanId());
            assertEquals(expectedSpanId, result.getSpanId());
            assertTrue(result.getEndEpochNanos() >= result.getStartEpochNanos());
            assertTrue(result.hasEnded());
            assertEquals(StatusCode.ERROR, result.getStatus().getStatusCode());
            assertEquals("", result.getStatus().getDescription());
            assertEquals("POST /api/internal/v1/ai-token-usages/reserve", result.getName());
            assertEquals(3, result.getAttributes().size());
            assertEquals(List.of(), result.getEvents());
            assertEquals(0, result.getTotalRecordedEvents());
            assertEquals("catchhole-backend-tracing-verification", result.getResource().getAttribute(AttributeKey.stringKey("service.name")));
            assertEquals(2, result.getResource().getAttributes().size());
            assertEquals("catchhole.java-agent.sanitized", result.getInstrumentationScopeInfo().getName());
            assertEquals("catchhole.java-agent.sanitized", result.getInstrumentationLibraryInfo().getName());
            assertFalse(result.toString().contains(SECRET));
            assertTrue(result.getSpanContext().getTraceState().isEmpty());
            assertFalse(result.getSpanContext().isRemote());
            assertTrue(result.getParentSpanContext().getTraceState().isEmpty());
            assertTrue(result.getParentSpanContext().isRemote());
            assertEquals(1, result.getLinks().size());
            assertTrue(result.getLinks().getFirst().getAttributes().isEmpty());
            assertTrue(result.getLinks().getFirst().getSpanContext().getTraceState().isEmpty());
            assertTrue(result.getLinks().getFirst().getSpanContext().isRemote());
            assertFalse(result.getAttributes().toString().contains(SECRET));
        }
    }

    @Test
    @DisplayName("실제 URL을 route로 복사하거나 자유 형식 메서드·DB 명령을 전달하지 않는다")
    void rejectsUnlistedRouteAndFreeFormValues() {
        CapturingExporter sink = new CapturingExporter();
        try (SdkTracerProvider provider = provider(sink)) {
            Span span = provider.get("fixture").spanBuilder(SECRET).setSpanKind(SpanKind.SERVER)
                    .setParent(Context.root().with(Span.wrap(REMOTE)))
                    .setAttribute("http.route", "/api/internal/v1/ai-token-usages/private-id/settle")
                    .setAttribute("http.request.method", SECRET)
                    .setAttribute("http.response.status_code", 12345)
                    .setAttribute("db.operation.name", "SELECT " + SECRET)
                    .startSpan();
            span.end();
            assertEquals("HTTP request", sink.spans.getFirst().getName());
            assertTrue(sink.spans.getFirst().getAttributes().isEmpty());
        }
    }

    @Test
    @DisplayName("JDBC의 구·신 속성명에서 고정 DB 종류와 명령만 남긴다")
    void keepsBoundedJdbcMeaningWithoutSql() {
        CapturingExporter sink = new CapturingExporter();
        try (SdkTracerProvider provider = provider(sink)) {
            Span span = provider.get("fixture").spanBuilder("SELECT private_table " + SECRET)
                    .setSpanKind(SpanKind.CLIENT).setParent(Context.root().with(Span.wrap(REMOTE)))
                    .setAttribute("db.system", "h2").setAttribute("db.operation", "SELECT")
                    .setAttribute("db.statement", "SELECT '" + SECRET + "'")
                    .setAttribute("db.query.text", "SELECT '" + SECRET + "'")
                    .setAttribute("db.connection_string", SECRET).startSpan();
            span.end();
            SpanData result = sink.spans.getFirst();
            assertEquals("DB SELECT", result.getName());
            assertEquals(Attributes.of(AttributeKey.stringKey("db.system.name"), "h2",
                    AttributeKey.stringKey("db.operation.name"), "SELECT"), result.getAttributes());
        }
    }

    @Test
    @DisplayName("새 root와 비선택 부모는 버리고 선택된 원격 부모의 자식만 전송한다")
    void followsSampledParentWithoutNewRoots() {
        CapturingExporter sink = new CapturingExporter();
        try (SdkTracerProvider provider = provider(sink)) {
            var tracer = provider.get("fixture");
            tracer.spanBuilder("root").setNoParent().startSpan().end();
            SpanContext unsampled = SpanContext.createFromRemoteParent(REMOTE.getTraceId(), REMOTE.getSpanId(),
                    TraceFlags.getDefault(), TraceState.getDefault());
            tracer.spanBuilder("unsampled").setParent(Context.root().with(Span.wrap(unsampled))).startSpan().end();
            tracer.spanBuilder("sampled").setParent(Context.root().with(Span.wrap(REMOTE))).startSpan().end();
            assertEquals(1, sink.spans.size());
            assertEquals(REMOTE.getTraceId(), sink.spans.getFirst().getTraceId());
        }
    }

    @Test
    @DisplayName("Agent 자동 구성 SPI가 별도 SDK 생성 없이 확장을 발견한다")
    void registersAgentCustomizer() {
        var providers = ServiceLoader.load(io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider.class)
                .stream().map(ServiceLoader.Provider::type).toList();
        assertTrue(providers.contains(PrivacyCustomizer.class));
    }

    @Test
    @DisplayName("local과 prod 및 프로세스 UUID만 보존하고 자유 형식 환경·인스턴스는 제거한다")
    void keepsOnlyApprovedDeploymentIdentity() {
        for (String environment : List.of("local", "prod", SECRET)) {
            CapturingExporter sink = new CapturingExporter();
            String instance = environment.equals(SECRET) ? SECRET : "12345678-1234-4234-8234-123456789abc";
            try (SdkTracerProvider provider = SdkTracerProvider.builder()
                    .setResource(Resource.create(Attributes.builder()
                            .put("service.name", "catchhole-backend")
                            .put("deployment.environment.name", environment)
                            .put("service.instance.id", instance)
                            .put("host.name", SECRET).build()))
                    .addSpanProcessor(SimpleSpanProcessor.create(new PrivacySpanExporter(sink))).build()) {
                provider.get("fixture").spanBuilder("example").startSpan().end();
                Attributes resource = sink.spans.getFirst().getResource().getAttributes();
                assertEquals(environment.equals(SECRET) ? "unknown" : environment,
                        resource.get(AttributeKey.stringKey("deployment.environment.name")));
                assertEquals(environment.equals(SECRET) ? null : instance,
                        resource.get(AttributeKey.stringKey("service.instance.id")));
                assertNull(resource.get(AttributeKey.stringKey("host.name")));
                assertFalse(resource.toString().contains(SECRET));
            }
        }
    }

    private static SdkTracerProvider provider(CapturingExporter sink) {
        return SdkTracerProvider.builder()
                .setSampler(Sampler.parentBased(Sampler.alwaysOff()))
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "catchhole-backend-tracing-verification",
                        AttributeKey.stringKey("process.command_args"), SECRET)))
                .addSpanProcessor(SimpleSpanProcessor.create(new PrivacySpanExporter(sink))).build();
    }

    private static final class CapturingExporter implements SpanExporter {
        final List<SpanData> spans = new ArrayList<>();
        public CompletableResultCode export(Collection<SpanData> batch) { spans.addAll(batch); return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
