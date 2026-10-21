package org.monitoring.catchholebackend.domain.relationship.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "사용자가 선택한 미등록 언급을 동일 인물로 명시적으로 묶기")
public record RelationshipMentionGroupCreateRequest(
        @Schema(description = "멱등 생성 요청 ID") @NotNull(message = "필수 항목을 입력해 주세요.") UUID requestId,
        @Schema(description = "사용자가 확인한 공통 표시 이름", example = "태조") @NotBlank(message = "표시 이름을 입력해 주세요.") @Size(max = 200, message = "200자 이하로 입력해 주세요.") String name,
        @Schema(description = "그룹에 속하지 않은 현재 표시 가능 후보 2~20개. 이름으로 자동 추가하지 않습니다")
        @NotNull(message = "묶을 후보를 선택해 주세요.") @Size(min = 2, max = 20, message = "후보는 2개 이상 20개 이하로 선택해 주세요.") List<@NotNull(message = "필수 항목을 입력해 주세요.") @Valid RelationshipMentionGroupMemberRequest> candidates
) {}
