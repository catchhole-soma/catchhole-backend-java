package org.monitoring.catchholebackend.global.config.validation;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.monitoring.catchholebackend.domain.validation.type.ValidationRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Disabled startup needs no budgets. Enabled execution requires every explicit limit.
 * Configuration v2 (#349 grid v4): output caps, models and reasoning efforts are per generation role
 * (PLANNER, JUDGE, TYPO); the branch limits no longer carry output tokens or a catalogue page size.
 */
@ConfigurationProperties("validation.execution")
public record ValidationExecutionProperties(boolean enabled, Duration leaseDuration, Integer maxLeaseRecoveries,
        Limits setting, Limits typo, Map<ValidationRole,RoleBudget> roles, String queryEmbeddingModel) {
    /** OpenAI Responses reasoning efforts the Worker accepts (validation_execution configurationV2). */
    public static final Set<String> REASONING_EFFORTS=Set.of("none","minimal","low","medium","high","xhigh");
    private static final String MODEL_PATTERN="[A-Za-z0-9._-]{1,100}";
    public record Limits(int modelCalls,int lookups,double seconds,int inputTokens,
            int segmentChars,Integer contextChars,int searchLimit) {
        public void validate() {
            if(modelCalls<1||lookups<1||!Double.isFinite(seconds)||seconds<=0||inputTokens<1
                    ||segmentChars<1||contextChars==null||contextChars<0||searchLimit<1||searchLimit>100)
                throw new IllegalArgumentException("검수 실행 예산을 명시적으로 설정해야 합니다.");
        }
    }
    /** Generation roles carry their own output cap, model and reasoning effort; QUERY_EMBEDDING leaves all three empty. */
    public record RoleBudget(int maxCalls,long maxReservedTokens,Integer outputTokens,String model,String reasoningEffort) {
        public void validate(ValidationRole role) {
            if(maxCalls<1||maxReservedTokens<1)throw new IllegalArgumentException("검수 역할 예산이 필요합니다.");
            if(role.generation()) {
                if(outputTokens==null||outputTokens<1||model==null||!model.matches(MODEL_PATTERN)||reasoningEffort==null||!REASONING_EFFORTS.contains(reasoningEffort))
                    throw new IllegalArgumentException("검수 생성 역할에는 출력 상한·모델·추론 강도가 필요합니다.");
            } else if(outputTokens!=null||model!=null||reasoningEffort!=null)throw new IllegalArgumentException("검수 임베딩 역할에는 출력 상한·모델·추론 강도를 두지 않습니다.");
        }
    }
    public ValidationExecutionProperties {
        roles=roles==null?Map.of():Map.copyOf(roles);
        if(enabled) {
            if(leaseDuration==null||leaseDuration.isNegative()||leaseDuration.isZero()||leaseDuration.toMillis()<1||maxLeaseRecoveries==null||maxLeaseRecoveries<0||setting==null||typo==null)
                throw new IllegalArgumentException("검수 실행 제한을 명시적으로 설정해야 합니다.");
            setting.validate();typo.validate();
            for(var role:ValidationRole.values()) {
                if(role.retired()) {
                    if(roles.containsKey(role))throw new IllegalArgumentException("검수 grid v4에서 없어진 역할 "+role.name()+"의 예산은 설정에서 빼야 합니다.");
                } else if(role.generation()) {
                    if(!roles.containsKey(role))throw new IllegalArgumentException("검수 역할 예산이 필요합니다.");
                    roles.get(role).validate(role);
                }
            }
            if(roles.containsKey(ValidationRole.QUERY_EMBEDDING)) {
                roles.get(ValidationRole.QUERY_EMBEDDING).validate(ValidationRole.QUERY_EMBEDDING);
                if(!"text-embedding-3-small".equals(queryEmbeddingModel))throw new IllegalArgumentException("기존 검색 색인과 호환되는 명시적 임베딩 모델이 필요합니다.");
            } else if(queryEmbeddingModel!=null&&!queryEmbeddingModel.isBlank())throw new IllegalArgumentException("검수 임베딩 예산이 필요합니다.");
        }
    }
}
