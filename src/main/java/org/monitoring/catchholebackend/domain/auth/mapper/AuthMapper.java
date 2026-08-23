package org.monitoring.catchholebackend.domain.auth.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.entity.MemberLegalRecord;
import org.monitoring.catchholebackend.domain.member.type.LegalDocumentType;
import org.springframework.stereotype.Component;

@Component
public class AuthMapper {

    public Member toEntity(
            AuthSignupRequest request,
            String passwordHash,
            String verifiedPhoneNumber
    ) {
        return Member.registerPhoneVerified(
                request.email(),
                passwordHash,
                verifiedPhoneNumber,
                request.displayName()
        );
    }

    public List<MemberLegalRecord> toLegalRecordEntities(Member member, LocalDateTime recordedAt) {
        return List.of(
                MemberLegalRecord.recordCurrent(member, LegalDocumentType.TERMS_OF_SERVICE, recordedAt),
                MemberLegalRecord.recordCurrent(member, LegalDocumentType.PRIVACY_POLICY, recordedAt)
        );
    }
}
