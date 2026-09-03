package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/** 단건·묶음 캐릭터 Fact 비교가 공유하는 operation 의미 계약을 검증한다. */
@Component
@RequiredArgsConstructor
public class CharacterFactComparisonDecisionValidator {

    private final CharacterSettingValueValidator valueValidator;

    public void validate(Decision decision) {
        CharacterFactOperation operation = decision.operation();
        validateTemporalScope(operation, decision.temporalScope());

        boolean proposedTextProvided = decision.proposedFactValue() != null;
        boolean hasProposedText = !isBlank(decision.proposedFactValue());
        boolean hasProposedJson = decision.proposedValueJson() != null;
        boolean hasRemoved = !decision.removedSlots().isEmpty();

        if (operation == CharacterFactOperation.ADD) {
            if (decision.explicitTargetProvided()
                    || decision.resolvedSlotExists()
                    || !hasProposedText
                    || !hasProposedJson) {
                throw invalidOperation();
            }
        } else if (operation == CharacterFactOperation.UPDATE
                || operation == CharacterFactOperation.MERGE) {
            if (!decision.explicitTargetProvided()
                    || !decision.targetExists()
                    || !hasProposedText
                    || !hasProposedJson) {
                throw invalidOperation();
            }
        } else if (operation == CharacterFactOperation.REMOVE) {
            if (decision.factType() != CharacterFactType.STATUS
                    || decision.explicitTargetProvided() && !decision.legacySameSlotRemove()
                    || !hasRemoved
                    || proposedTextProvided
                    || hasProposedJson
                    || decision.temporalScope() != CharacterFactTemporalScope.PRESENT) {
                throw invalidOperation();
            }
        } else if (decision.explicitTargetProvided()
                || hasRemoved
                || proposedTextProvided
                || hasProposedJson) {
            throw invalidOperation();
        }

        if (hasRemoved && operation != CharacterFactOperation.REMOVE) {
            if (decision.factType() != CharacterFactType.STATUS
                    || decision.temporalScope() != CharacterFactTemporalScope.PRESENT
                    || !upsertsSnapshot(operation)
                    || decision.removedSlots().contains(decision.resolvedSlot())) {
                throw invalidOperation();
            }
        }
        if (decision.removedSlots().stream()
                .anyMatch(slot -> slot.factType() != CharacterFactType.STATUS)) {
            throw invalidOperation();
        }

        if (upsertsSnapshot(operation)) {
            if (decision.factType() == CharacterFactType.STATUS
                    && (isExplicitlyInactiveStatus(decision.candidateValueJson())
                    || isExplicitlyInactiveStatus(decision.proposedValueJson()))) {
                throw invalidOperation();
            }
            valueValidator.validateProposal(
                    decision.proposedValueJson(),
                    decision.proposedFactValue(),
                    decision.factType(),
                    decision.valueType()
            );
        }
    }

    private void validateTemporalScope(
            CharacterFactOperation operation,
            CharacterFactTemporalScope temporalScope
    ) {
        if ((temporalScope == CharacterFactTemporalScope.PAST
                || temporalScope == CharacterFactTemporalScope.HYPOTHETICAL)
                && operation != CharacterFactOperation.HISTORY_ONLY
                && operation != CharacterFactOperation.REVIEW_REQUIRED
                || temporalScope == CharacterFactTemporalScope.UNKNOWN
                && operation != CharacterFactOperation.REVIEW_REQUIRED) {
            throw invalidOperation();
        }
    }

    private boolean upsertsSnapshot(CharacterFactOperation operation) {
        return operation == CharacterFactOperation.ADD
                || operation == CharacterFactOperation.UPDATE
                || operation == CharacterFactOperation.MERGE;
    }

    private boolean isExplicitlyInactiveStatus(JsonNode valueJson) {
        JsonNode active = valueJson == null || !valueJson.isObject() ? null : valueJson.get("active");
        return active != null && active.isBoolean() && !active.booleanValue();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AppException invalidOperation() {
        return new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
    }

    public record Decision(
            CharacterFactOperation operation,
            CharacterFactTemporalScope temporalScope,
            CharacterFactType factType,
            SettingValueType valueType,
            CharacterSnapshotSlot resolvedSlot,
            boolean explicitTargetProvided,
            boolean targetExists,
            boolean resolvedSlotExists,
            boolean legacySameSlotRemove,
            List<CharacterSnapshotSlot> removedSlots,
            String proposedFactValue,
            JsonNode proposedValueJson,
            JsonNode candidateValueJson
    ) {
    }
}
