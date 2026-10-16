package org.monitoring.catchholebackend.domain.aitoken.type;

import java.math.BigDecimal;

/** Server-owned conversion of raw provider tokens into member allowance units. */
public record AiTokenChargePolicy(String version, int denominator) {

    private static final int LUNA_DENOMINATOR = 20;
    private static final String SERVICE_PAID_VERSION = "SERVICE_PAID_V1";

    public AiTokenChargePolicy {
        if (version == null || version.isBlank() || (denominator != 1 && denominator != LUNA_DENOMINATOR)) {
            throw new IllegalArgumentException("Invalid AI token charge policy");
        }
    }

    public static AiTokenChargePolicy forModel(String model) {
        return new AiTokenChargePolicy("MODEL_WEIGHTED_V1", "gpt-6-luna".equals(model) ? LUNA_DENOMINATOR : 1);
    }

    public static AiTokenChargePolicy legacy() {
        return new AiTokenChargePolicy("LEGACY_RAW_V1", 1);
    }

    public static AiTokenChargePolicy servicePaid() {
        return new AiTokenChargePolicy(SERVICE_PAID_VERSION, 1);
    }

    public BigDecimal convert(long rawTokens) {
        if (rawTokens < 0) throw new IllegalArgumentException("Token count must not be negative");
        return SERVICE_PAID_VERSION.equals(version)
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(rawTokens).divide(BigDecimal.valueOf(denominator));
    }

    /** The model is not known at job submission; the actual locked reservation is authoritative. */
    public static BigDecimal minimumCharge(long rawMinimum) {
        return forModel("gpt-6-luna").convert(rawMinimum);
    }
}
