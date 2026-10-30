package org.monitoring.catchholebackend.global.config.validation;

import java.time.Duration;
import java.util.Map;
import org.monitoring.catchholebackend.domain.validation.type.ValidationRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Disabled startup needs no budgets. Enabled execution requires every explicit limit. */
@ConfigurationProperties("validation.execution")
public record ValidationExecutionProperties(boolean enabled, Duration leaseDuration, Integer maxLeaseRecoveries,
        Limits setting, Limits typo, Map<ValidationRole,RoleBudget> roles, String queryEmbeddingModel) {
    public record Limits(int modelCalls,int lookups,double seconds,int inputTokens,int outputTokens,
            int segmentChars,Integer contextChars,int cataloguePageSize,int searchLimit) {
        public void validate() {
            if(modelCalls<1||lookups<1||!Double.isFinite(seconds)||seconds<=0||inputTokens<1||outputTokens<1
                    ||segmentChars<1||contextChars==null||contextChars<0||cataloguePageSize<1||cataloguePageSize>100||searchLimit<1||searchLimit>100)
                throw new IllegalArgumentException("검수 실행 예산을 명시적으로 설정해야 합니다.");
        }
    }
    public record RoleBudget(int maxCalls,long maxReservedTokens) {
        public void validate() { if(maxCalls<1||maxReservedTokens<1)throw new IllegalArgumentException("검수 역할 예산이 필요합니다."); }
    }
    public ValidationExecutionProperties {
        roles=roles==null?Map.of():Map.copyOf(roles);
        if(enabled) {
            if(leaseDuration==null||leaseDuration.isNegative()||leaseDuration.isZero()||leaseDuration.toMillis()<1||maxLeaseRecoveries==null||maxLeaseRecoveries<0||setting==null||typo==null)
                throw new IllegalArgumentException("검수 실행 제한을 명시적으로 설정해야 합니다.");
            setting.validate();typo.validate();
            for(var role:ValidationRole.values()) if(role!=ValidationRole.QUERY_EMBEDDING) {
                if(!roles.containsKey(role))throw new IllegalArgumentException("검수 역할 예산이 필요합니다.");
                roles.get(role).validate();
            }
            if(roles.containsKey(ValidationRole.QUERY_EMBEDDING)) {
                roles.get(ValidationRole.QUERY_EMBEDDING).validate();
                if(!"text-embedding-3-small".equals(queryEmbeddingModel))throw new IllegalArgumentException("기존 검색 색인과 호환되는 명시적 임베딩 모델이 필요합니다.");
            } else if(queryEmbeddingModel!=null&&!queryEmbeddingModel.isBlank())throw new IllegalArgumentException("검수 임베딩 예산이 필요합니다.");
        }
    }
}
