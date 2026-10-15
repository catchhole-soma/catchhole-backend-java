package org.monitoring.catchholebackend.domain.analysis.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

/** Claim 전용 읽기 문맥이다. 값과 identity는 모두 같은 고정 입력에서 유래한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "claim 전용 전체 고정 CURRENT 문맥. 누락/부분 문맥을 빈 CURRENT로 취급하지 않습니다.",
        requiredProperties = {"formatVersion", "runId", "generation", "inputStateHash", "sourceHash", "complete", "characters", "worldSubjects"})
public record WorkerSemanticCurrentContextPayload(
        int formatVersion,
        UUID runId,
        long generation,
        String inputStateHash,
        String sourceHash,
        boolean complete,
        List<Character> characters,
        List<WorldSubject> worldSubjects
) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "WorkerSemanticCurrentCharacterPayload", requiredProperties = {"characterId", "provisionalSubjectKey", "currentSlots"})
    public record Character(@Schema(nullable = true) UUID characterId,
            @Schema(nullable = true) String provisionalSubjectKey, List<CurrentSlot> currentSlots) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "WorkerSemanticCurrentSlotPayload", requiredProperties = {"factType", "factKey", "factValue", "valueJson"})
    public record CurrentSlot(CharacterFactType factType, String factKey,
            @Schema(nullable = true) String factValue,
            @Schema(types = {"object", "array", "string", "number", "boolean", "null"}) Object valueJson) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "WorkerSemanticCurrentWorldSubjectPayload", requiredProperties = {"subjectId", "provisionalSubjectKey", "category", "subjectName", "properties"})
    public record WorldSubject(@Schema(nullable = true) UUID subjectId,
            @Schema(nullable = true) String provisionalSubjectKey, WorldSettingCategory category,
            String subjectName, List<Property> properties) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "WorkerSemanticCurrentWorldPropertyPayload", requiredProperties = {"scopeName", "settingName", "value"})
    public record Property(@Schema(nullable = true) String scopeName, String settingName, String value) {}
}
