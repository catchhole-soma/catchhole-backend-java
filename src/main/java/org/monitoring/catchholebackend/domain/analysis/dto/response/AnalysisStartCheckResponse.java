package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "새 회차 분석 전 검토 조건. 직접 검토는 완료해야 하고 애매한 후보 종료에는 명시적인 동의가 필요합니다.")
public record AnalysisStartCheckResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "요청한 회차 번호의 최댓값보다 앞선 회차에서 대기·실행 중인 설정 추출 작업 수. 0보다 크면 해당 분석이 끝난 뒤 시작해야 합니다.", example = "0")
        long activeEarlierAnalysisCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "요청한 회차 번호의 최댓값보다 앞선 회차의 필수 검토 캐릭터·세계관 후보 수. 직접 검토·작가 수정 등을 반영하거나 제외해야 하며, 계속 분석 동의로 종료할 수 없습니다.", example = "2")
        long manualReviewCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "요청한 회차 번호의 최솟값보다 앞선 회차의 자동 분석이 정상 종료되어 명시적으로 종료할 수 있는 애매한 캐릭터 후보 수. 경고에 동의하면 종료하며 기술적 실패·처리 중 후보는 포함하지 않습니다.", example = "1")
        long ambiguousCharacterCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "요청한 회차 번호의 최솟값보다 앞선 회차의 자동 분석이 정상 종료되어 명시적으로 종료할 수 있는 애매한 세계관 후보 수. 경고에 동의하면 종료하며 기술적 실패·처리 중 후보는 포함하지 않습니다.", example = "0")
        long ambiguousWorldCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "요청한 회차 번호의 최솟값보다 앞선 회차의 정상적인 관계 HOLD 수. 안내용이며 다음 회차의 관계 분석 참고로 유지합니다. 기술적 비교 실패는 포함하지 않습니다.", example = "1")
        long ambiguousRelationshipCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "작품·정렬된 전체 대상 회차·후보 상태에 결속된 확인 값. 종료에 동의할 때 생성 요청의 reviewGateRevision으로 그대로 보냅니다.",
                example = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        String revision
) {}
