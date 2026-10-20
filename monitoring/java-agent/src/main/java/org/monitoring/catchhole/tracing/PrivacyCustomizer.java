package org.monitoring.catchhole.tracing;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/** Agent-owned SDK extension: never installs a second SDK in the Spring application. */
public final class PrivacyCustomizer implements AutoConfigurationCustomizerProvider {
    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addSpanExporterCustomizer((exporter, configuration) -> new PrivacySpanExporter(exporter));
    }
}
