package org.monitoring.catchholebackend.domain.relationship.processor;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * A committed fingerprint manifest authorizes only the frozen cohort; never broadens on startup.
 */
@Component
public class RelationshipBackfillManifest {
    private final String campaignKey;
    private final LocalDateTime selectedAt;
    private final Set<String> fingerprints;

    public RelationshipBackfillManifest() {
        try (var in =
                new ClassPathResource(
                                "relationship-backfill/relationship-backfill-20261030-v1.json")
                        .getInputStream()) {
            var json = new ObjectMapper().readTree(in);
            campaignKey = json.path("campaignKey").asText();
            selectedAt = LocalDateTime.parse(json.path("selectedAt").asText());
            var values = new HashSet<String>();
            json.path("memberFingerprints")
                    .forEach(
                            n -> {
                                if (!n.asText().matches("[0-9a-f]{64}") || !values.add(n.asText()))
                                    throw new IllegalStateException(
                                            "Invalid relationship backfill manifest");
                            });
            if (!campaignKey.equals("relationship-backfill-20261030-v1")
                    || values.size() != json.path("memberCount").asInt()
                    || values.size() != 27)
                throw new IllegalStateException("Invalid relationship backfill manifest");
            fingerprints = Set.copyOf(values);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load relationship backfill manifest", e);
        }
    }

    public String campaignKey() {
        return campaignKey;
    }

    public LocalDateTime selectedAt() {
        return selectedAt;
    }

    public boolean matches(Member member) {
        return member.getStatus() == MemberStatus.ACTIVE
                && member.getCreatedAt() != null
                && fingerprints.contains(
                        fingerprint(campaignKey, member.getId(), member.getCreatedAt()));
    }

    public static String fingerprint(String campaignKey, Long memberId, LocalDateTime createdAt) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (campaignKey
                                                            + "\n"
                                                            + memberId
                                                            + "\n"
                                                            + createdAt.format(
                                                                    DateTimeFormatter.ofPattern(
                                                                            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
