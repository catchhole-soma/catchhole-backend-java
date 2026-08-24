package org.monitoring.catchholebackend.domain.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.service.SignupLegalDocuments;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.type.LegalRecordAction;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("인증 Mapper 단위 테스트")
class AuthMapperTest {

    private final AuthMapper authMapper = new AuthMapper();

    @Test
    @DisplayName("인증된 전화번호와 암호화한 비밀번호로 회원가입 Entity를 조립한다")
    void toEntityCreatesPhoneVerifiedMember() {
        AuthSignupRequest request = new AuthSignupRequest(
                "writer@example.com",
                "password123",
                "작가",
                true,
                true,
                true,
                3L,
                4L,
                "phone-verification-token"
        );

        LocalDateTime confirmedAt = LocalDateTime.of(2026, 8, 24, 17, 0);
        Member member = authMapper.toEntity(request, "encoded-password", "01012345678", confirmedAt);

        assertThat(member.getEmail()).isEqualTo("writer@example.com");
        assertThat(member.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(member.getPhoneNumber()).isEqualTo("01012345678");
        assertThat(member.getDisplayName()).isEqualTo("작가");
        assertThat(member.isPhoneVerified()).isTrue();
        assertThat(member.getAgeRequirementConfirmedAt()).isEqualTo(confirmedAt);
    }

    @Test
    @DisplayName("현재 약관 동의와 개인정보처리방침 확인 이력을 각각 조립한다")
    void toLegalRecordEntitiesCreatesSignupLegalEvidence() {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 8, 23, 10, 30);
        Member member = Member.registerPhoneVerified(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가",
                recordedAt
        );
        LegalDocument terms = document(3L, LegalDocumentType.TERMS_OF_SERVICE);
        LegalDocument privacy = document(4L, LegalDocumentType.PRIVACY_POLICY);

        var records = authMapper.toLegalRecordEntities(
                member,
                new SignupLegalDocuments(terms, privacy),
                recordedAt
        );

        assertThat(records)
                .extracting(
                        record -> record.getDocumentType(),
                        record -> record.getActionType(),
                        record -> record.getDocumentVersion(),
                        record -> record.getRecordedAt()
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                LegalDocumentType.TERMS_OF_SERVICE,
                                LegalRecordAction.AGREED,
                                "2026-08-24",
                                recordedAt
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                LegalDocumentType.PRIVACY_POLICY,
                                LegalRecordAction.ACKNOWLEDGED,
                                "2026-08-24",
                                recordedAt
                        )
                );
        assertThat(records)
                .extracting(record -> record.getLegalDocument().getId())
                .containsExactly(3L, 4L);
    }

    private LegalDocument document(Long id, LegalDocumentType type) {
        LegalDocument document = LegalDocument.published(
                type,
                "ko-KR",
                "2026-08-24",
                type == LegalDocumentType.TERMS_OF_SERVICE ? "CatchHole 이용약관" : "CatchHole 개인정보처리방침",
                "# 원문",
                "a".repeat(64),
                LocalDate.of(2026, 8, 24),
                LocalDateTime.of(2026, 8, 24, 18, 0)
        );
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }
}
