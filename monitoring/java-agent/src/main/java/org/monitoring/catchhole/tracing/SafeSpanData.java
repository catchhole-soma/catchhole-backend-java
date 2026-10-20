package org.monitoring.catchhole.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.List;
import java.util.Set;

/** A deliberately small export schema for HTTP server and JDBC observations. */
final class SafeSpanData extends DelegatingSpanData {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE");
    private static final Set<String> OPERATIONS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL", "EXECUTE", "COMMIT", "ROLLBACK", "CREATE", "ALTER", "DROP", "TRUNCATE", "SET", "SHOW", "BEGIN", "SAVEPOINT", "RELEASE");
    private static final Set<String> DATABASES = Set.of("postgresql", "h2");
    // Literal controller templates, never arbitrary paths or URL-derived route guesses.
    private static final Set<String> ROUTES = Set.of(
            "/api/internal/v1/ai-token-usages/reserve",
            "/api/internal/v1/ai-token-usages/{requestId}/settle",
            "/api/internal/v1/ai-token-usages/{requestId}/release",
            "/api/internal/v1/analysis-jobs/{analysisJobId}/heartbeat",
            "/api/internal/v1/analysis-jobs/{analysisJobId}/progress",
            "/api/internal/v1/analysis-jobs/{analysisJobId}/complete",
            "/api/internal/v1/analysis-jobs/{analysisJobId}/fail");
    private static final Set<String> SERVICES = Set.of("catchhole-backend", "catchhole-backend-tracing-verification");
    private static final Set<String> ENVIRONMENTS = Set.of("local", "prod");
    private static final InstrumentationScopeInfo SCOPE = InstrumentationScopeInfo.create("catchhole.java-agent.sanitized");

    private final Attributes attributes;
    private final String name;
    private final List<LinkData> links;
    private final Resource resource;

    SafeSpanData(SpanData original) {
        super(original);
        AttributesBuilder safe = Attributes.builder();
        String method = first(original, "http.request.method", "http.method");
        String route = first(original, "http.route");
        String operation = first(original, "db.operation.name", "db.operation");
        String database = first(original, "db.system.name", "db.system");

        if (method != null && METHODS.contains(method)) {
            safe.put("http.request.method", method);
        } else {
            method = null;
        }
        // Only framework route templates; never derive a route from url.full/http.target.
        if (route != null && ROUTES.contains(route)) {
            safe.put("http.route", route);
        } else {
            route = null;
        }
        Long status = original.getAttributes().get(AttributeKey.longKey("http.response.status_code"));
        if (status == null) status = original.getAttributes().get(AttributeKey.longKey("http.status_code"));
        if (status != null && status >= 100 && status <= 599) safe.put("http.response.status_code", status);
        if (operation != null && OPERATIONS.contains(operation)) {
            safe.put("db.operation.name", operation);
        } else {
            operation = null;
        }
        if (database != null && DATABASES.contains(database)) safe.put("db.system.name", database);

        attributes = safe.build();
        name = original.getKind() == SpanKind.SERVER
                ? (method == null ? "HTTP" : method) + (route == null ? " request" : " " + route)
                : database != null || operation != null
                    ? "DB " + (operation == null ? "operation" : operation)
                    : "internal.operation";
        links = original.getLinks().stream().map(link -> LinkData.create(cleanContext(link.getSpanContext()))).toList();
        String service = original.getResource().getAttribute(AttributeKey.stringKey("service.name"));
        String environment = original.getResource().getAttribute(AttributeKey.stringKey("deployment.environment.name"));
        String instance = original.getResource().getAttribute(AttributeKey.stringKey("service.instance.id"));
        AttributesBuilder safeResource = Attributes.builder()
                .put("service.name", SERVICES.contains(service == null ? "" : service) ? service : "catchhole-backend")
                .put("deployment.environment.name", ENVIRONMENTS.contains(environment == null ? "" : environment) ? environment : "unknown");
        // The production entrypoint generates a fresh UUID for each process start.
        // Never export hostname, command line or arbitrary resource attributes.
        if (instance != null && instance.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            safeResource.put("service.instance.id", instance);
        }
        resource = Resource.create(safeResource.build());
    }

    private static String first(SpanData span, String... keys) {
        for (String key : keys) {
            String value = span.getAttributes().get(AttributeKey.stringKey(key));
            if (value != null) return value;
        }
        return null;
    }

    private static SpanContext cleanContext(SpanContext context) {
        return context.isRemote()
                ? SpanContext.createFromRemoteParent(context.getTraceId(), context.getSpanId(), context.getTraceFlags(), TraceState.getDefault())
                : SpanContext.create(context.getTraceId(), context.getSpanId(), context.getTraceFlags(), TraceState.getDefault());
    }

    @Override public String getName() { return name; }
    @Override public Attributes getAttributes() { return attributes; }
    @Override public int getTotalAttributeCount() { return attributes.size(); }
    @Override public List<EventData> getEvents() { return List.of(); }
    @Override public int getTotalRecordedEvents() { return 0; }
    @Override public List<LinkData> getLinks() { return links; }
    @Override public int getTotalRecordedLinks() { return links.size(); }
    @Override public StatusData getStatus() { return StatusData.create(super.getStatus().getStatusCode(), ""); }
    @Override public Resource getResource() { return resource; }
    @Override public InstrumentationScopeInfo getInstrumentationScopeInfo() { return SCOPE; }
    @Override
    @SuppressWarnings("deprecation")
    public io.opentelemetry.sdk.common.InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
        return io.opentelemetry.sdk.common.InstrumentationLibraryInfo.create(SCOPE.getName(), null);
    }
    @Override public SpanContext getSpanContext() { return cleanContext(super.getSpanContext()); }
    @Override public SpanContext getParentSpanContext() { return cleanContext(super.getParentSpanContext()); }

    @Override
    public String toString() {
        // DelegatingSpanData's default string representation includes the original payload.
        return "SafeSpanData{traceId=" + getTraceId() + ", spanId=" + getSpanId()
                + ", name=" + name + ", attributes=" + attributes + "}";
    }
}
