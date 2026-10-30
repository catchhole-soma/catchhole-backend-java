package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "회차 전체 확정 보류 사유 하나. 보류된 시도는 어떤 설정도 반영하지 않았습니다.")
public record ValidationApplicationHoldReason(
        ValidationApplicationHoldCode code,
        @Schema(description = "CHARACTER·WORLD_SETTING 후보 또는 회차 단위(EPISODE)") ValidationApplicationCandidateType candidateType,
        @Schema(description = "후보 ID. 회차 단위 사유는 null", nullable = true) UUID candidateId,
        @Schema(description = "원래 보류·실패 코드 같은 보조 식별자. 화면 문구가 아닙니다.", nullable = true, example = "CURRENT_SETTING_CHANGED") String detail) {
    public static ValidationApplicationHoldReason episode(ValidationApplicationHoldCode code,String detail) {return new ValidationApplicationHoldReason(code,ValidationApplicationCandidateType.EPISODE,null,detail);}
}
