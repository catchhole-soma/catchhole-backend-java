package org.monitoring.catchholebackend.domain.upload.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;

@Schema(description = "설정집 원문 수정 요청")
public record SettingBookUpdateRequest(
        @Schema(description = "수정할 설정집 전체 원문", example = "세계관 규칙과 인물 설정 원문")
        @NotBlank(message = "설정집 원문은 필수입니다.")
        @Size(max = 10485760, message = "설정집 원문은 10MB 이하여야 합니다.")
        String content,
        @Schema(description = "기준 시점. 생략하면 현재 저장 시점을 유지합니다.", nullable = true)
        SettingBookReferenceTimeKind referenceTimeKind,
        @Schema(description = "특정 회차 종료 시점의 양수 회차 번호. 등록된 회차가 없어도 입력할 수 있습니다.", nullable = true)
        Integer referenceEpisodeNo,
        @Schema(description = "편집 시작 시 읽은 revisionNo. 불변 revision 없는 구형 설정집은 0입니다. 최신 조회 번호로 덮어쓰지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
        @NotNull(message = "편집 시작 시 설정집 버전은 필수입니다.")
        @Min(value = 0, message = "설정집 버전은 0 이상이어야 합니다.")
        Integer expectedRevisionNo
) {
}
