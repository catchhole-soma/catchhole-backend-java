package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관계 백필 고정 계정 명단의 식별 규약")
class RelationshipBackfillManifestTest {
    @Test
    @DisplayName("회원 가입 시각은 PostgreSQL과 같은 마이크로초 여섯 자리로 해시한다")
    void fingerprintUsesFixedMicrosecondPrecision() {
        assertThat(RelationshipBackfillManifest.fingerprint(
                "relationship-backfill-20261030-v1", 42L,
                LocalDateTime.parse("2026-01-02T03:04:05.000007")))
                .isEqualTo("aa5498eab69e55cb1251291df8b9e2f106c33045450b9451580e80b1621e81de");
        assertThat(RelationshipBackfillManifest.fingerprint(
                "relationship-backfill-20261030-v1", 42L,
                LocalDateTime.parse("2026-01-02T03:04:05.000008")))
                .isNotEqualTo("aa5498eab69e55cb1251291df8b9e2f106c33045450b9451580e80b1621e81de");
    }

    @Test
    @DisplayName("JAR에 포함한 명단은 고정 캠페인과 선정 시각으로 로드한다")
    void loadsCommittedManifest() {
        var manifest = new RelationshipBackfillManifest();
        assertThat(manifest.campaignKey()).isEqualTo("relationship-backfill-20261030-v1");
        assertThat(manifest.selectedAt()).isEqualTo(LocalDateTime.parse("2026-10-30T16:13:03.339057"));
    }
}
