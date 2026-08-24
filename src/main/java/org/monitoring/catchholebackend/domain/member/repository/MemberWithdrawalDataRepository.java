package org.monitoring.catchholebackend.domain.member.repository;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MemberWithdrawalDataRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 회원 hard delete를 막는 직접 참조와 회원 소유 계정 데이터를 정리한다.
     * 작품에 속한 데이터는 이 메서드가 아니라 기존 WorkPurge가 먼저 삭제한다.
     */
    public void purgeMemberReferences(Long memberId, LocalDateTime now) {
        jdbcTemplate.update(
                "update world_setting_candidates set reviewed_by = null, updated_at = ? where reviewed_by = ?",
                now,
                memberId
        );
        jdbcTemplate.update("delete from refresh_tokens where member_id = ?", memberId);
        jdbcTemplate.update("delete from ai_token_usages where member_id = ?", memberId);
        jdbcTemplate.update("delete from ai_token_grants where member_id = ?", memberId);
        jdbcTemplate.update("delete from ai_token_accounts where member_id = ?", memberId);
        jdbcTemplate.update("delete from member_legal_records where member_id = ?", memberId);
    }
}
