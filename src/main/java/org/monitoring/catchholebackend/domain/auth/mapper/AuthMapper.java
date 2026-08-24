package org.monitoring.catchholebackend.domain.auth.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.legal.service.SignupLegalDocuments;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.entity.MemberLegalRecord;
import org.springframework.stereotype.Component;

@Component
public class AuthMapper {

    public Member toEntity(
            AuthSignupRequest request,
            String passwordHash,
            String verifiedPhoneNumber,
            LocalDateTime ageRequirementConfirmedAt
    ) {
        return Member.registerPhoneVerified(
                request.email(),
                passwordHash,
                verifiedPhoneNumber,
                request.displayName(),
                ageRequirementConfirmedAt
        );
    }

    public List<MemberLegalRecord> toLegalRecordEntities(
            Member member,
            SignupLegalDocuments documents,
            LocalDateTime recordedAt
    ) {
        return List.of(
                MemberLegalRecord.record(member, documents.termsOfService(), recordedAt),
                MemberLegalRecord.record(member, documents.privacyPolicy(), recordedAt)
        );
    }
}
