package org.monitoring.catchhole.tracing;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;

/** Removes free-form data before any configured exporter sees it. */
public final class PrivacySpanExporter implements SpanExporter {
    private final SpanExporter delegate;

    public PrivacySpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        final List<SpanData> sanitized;
        try {
            sanitized = spans.stream().<SpanData>map(SafeSpanData::new).toList();
        } catch (RuntimeException error) {
            // Fail closed: never fall back to exporting the original payload or exception message.
            return CompletableResultCode.ofFailure();
        }
        return delegate.export(sanitized);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}
