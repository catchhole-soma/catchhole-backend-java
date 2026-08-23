package org.monitoring.catchholebackend.domain.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.type.LegalRecordAction;

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
                "phone-verification-token"
        );

        Member member = authMapper.toEntity(request, "encoded-password", "01012345678");

        assertThat(member.getEmail()).isEqualTo("writer@example.com");
        assertThat(member.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(member.getPhoneNumber()).isEqualTo("01012345678");
        assertThat(member.getDisplayName()).isEqualTo("작가");
        assertThat(member.isPhoneVerified()).isTrue();
    }

    @Test
    @DisplayName("현재 약관 동의와 개인정보처리방침 확인 이력을 각각 조립한다")
    void toLegalRecordEntitiesCreatesSignupLegalEvidence() {
        Member member = Member.registerPhoneVerified(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        );
        LocalDateTime recordedAt = LocalDateTime.of(2026, 8, 23, 10, 30);

        var records = authMapper.toLegalRecordEntities(member, recordedAt);

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
                                "2026-08-23",
                                recordedAt
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                LegalDocumentType.PRIVACY_POLICY,
                                LegalRecordAction.ACKNOWLEDGED,
                                "2026-08-23",
                                recordedAt
                        )
                );
    }
}
