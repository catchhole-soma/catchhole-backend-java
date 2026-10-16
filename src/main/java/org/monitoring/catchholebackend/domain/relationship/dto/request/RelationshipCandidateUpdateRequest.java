package org.monitoring.catchholebackend.domain.relationship.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

@Schema(description = "가족 관계 후보 수정")
public record RelationshipCandidateUpdateRequest(
        @Schema(description = "조회 당시 후보 버전") @NotNull(message = "필수 항목을 입력해 주세요.") @PositiveOrZero(message = "버전은 0 이상이어야 합니다.") Long expectedVersion,
        @Schema(description = "관계를 서술하는 캐릭터 ID") @NotNull(message = "필수 항목을 입력해 주세요.") UUID subjectCharacterId,
        @Schema(description = "상대 캐릭터 ID. 미해결이면 비워 둡니다") UUID objectCharacterId,
        @Schema(description = "미해결 상대의 원래 호칭. 이름으로 자동 연결하지 않습니다") @Size(max = 200, message = "200자 이하로 입력해 주세요.") String objectMention,
        @Schema(description = "관계 종류. CHILD_OF는 확정할 때 방향을 뒤집습니다") @NotNull(message = "필수 항목을 입력해 주세요.") RelationshipKind kind,
        @Schema(description = "명시된 세부 관계. 불명확하면 UNSPECIFIED") @NotNull(message = "필수 항목을 입력해 주세요.") RelationshipSubtype subtype,
        @Schema(description = "서사상의 현재/과거/불명. 발견 회차와 별개입니다") @NotNull(message = "필수 항목을 입력해 주세요.") RelationshipTemporalScope temporalScope,
        @Schema(description = "설정에 명시된 성립 시점 또는 기간. 발견 회차로 추정하지 않습니다") @Size(max = 200, message = "200자 이하로 입력해 주세요.") String timeLabel,
        @Schema(description = "명시적 단정 여부. ASSERTED만 확정할 수 있습니다") @NotNull(message = "필수 항목을 입력해 주세요.") RelationshipAssertion assertion,
        @Schema(description = "현재 프로필의 근거 Fact와 조회 당시 토큰") @NotEmpty(message = "관계 근거를 한 개 이상 선택해 주세요.") @Size(max = 20, message = "관계 근거는 20개 이하로 선택해 주세요.") List<@NotNull(message = "필수 항목을 입력해 주세요.") @Valid RelationshipEvidenceRequest> evidence
) implements RelationshipDraft {}
