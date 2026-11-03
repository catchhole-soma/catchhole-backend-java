package org.monitoring.catchholebackend.global.config.validation;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.validation.type.ValidationRole;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.*;

@DisplayName("검수 실행의 명시적 설정과 기본 비활성")
class ValidationExecutionPropertiesTest {
    Limits limits() {return new Limits(10,20,30,1000,100,10,10);}
    static RoleBudget generation(String model,String effort) {return new RoleBudget(10,10000,100,model,effort);}
    Map<ValidationRole,RoleBudget> roles() {
        var roles=new EnumMap<ValidationRole,RoleBudget>(ValidationRole.class);
        roles.put(ValidationRole.PLANNER,generation("gpt-6.1-terra","medium"));roles.put(ValidationRole.JUDGE,generation("gpt-6-luna","high"));roles.put(ValidationRole.TYPO,generation("gpt-6.1-terra","low"));
        return roles;
    }
    ValidationExecutionProperties enabled(Map<ValidationRole,RoleBudget> roles,String embedding) {return new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,limits(),limits(),roles,embedding);}
    @Test @DisplayName("비활성 앱은 검수 예산 없이 기동한다") void disabledNeedsNoExecutionConfiguration() {
        assertThatCode(()->new ValidationExecutionProperties(false,null,null,null,null,null,null)).doesNotThrowAnyException();
    }
    @Test @DisplayName("비활성 앱은 남아 있는 퇴역 역할 키가 binding되어도 기동한다") void disabledToleratesStaleRetiredRoleKeys() {
        var roles=roles();roles.put(ValidationRole.COVERAGE,new RoleBudget(40,2_400_000,null,null,null));
        assertThatCode(()->new ValidationExecutionProperties(false,null,null,null,null,roles,null)).doesNotThrowAnyException();
    }
    @Test @DisplayName("활성 앱은 빠진 제한이나 생성 역할 예산을 거절한다") void enabledRequiresAllExplicitLimits() {
        assertThatThrownBy(()->new ValidationExecutionProperties(true,null,null,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->enabled(roles(),null)).doesNotThrowAnyException();
        for(var role:List.of(ValidationRole.PLANNER,ValidationRole.JUDGE,ValidationRole.TYPO)) {var roles=roles();roles.remove(role);
            assertThatThrownBy(()->enabled(roles,null)).as(role.name()).isInstanceOf(IllegalArgumentException.class).hasMessage("검수 역할 예산이 필요합니다.");}
        var unsetContext=new Limits(10,20,30,1000,100,null,10);
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,unsetContext,limits(),roles(),null)).isInstanceOf(IllegalArgumentException.class);
        var wideSearch=new Limits(10,20,30,1000,100,10,101);
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,wideSearch,limits(),roles(),null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test @DisplayName("생성 역할은 출력 상한·모델·추론 강도를 모두 요구하고 알 수 없는 강도를 거절한다") void generationRolesNeedOutputModelAndEffort() {
        for(var broken:List.of(new RoleBudget(10,10000,null,"gpt-6.1-terra","medium"),new RoleBudget(10,10000,0,"gpt-6.1-terra","medium"),
                new RoleBudget(10,10000,100,null,"medium"),new RoleBudget(10,10000,100,"gpt 6",  "medium"),new RoleBudget(10,10000,100,"gpt-6.1-terra",null),
                new RoleBudget(10,10000,100,"gpt-6.1-terra","max"))) {
            var roles=roles();roles.put(ValidationRole.JUDGE,broken);
            assertThatThrownBy(()->enabled(roles,null)).as(broken.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        for(var effort:ValidationExecutionProperties.REASONING_EFFORTS) {var roles=roles();roles.put(ValidationRole.JUDGE,generation("gpt-6-luna",effort));assertThatCode(()->enabled(roles,null)).doesNotThrowAnyException();}
    }
    @Test @DisplayName("활성 앱은 grid v4에서 없어진 RELATED_SELECTION·COVERAGE 예산을 명시적으로 거절한다") void enabledRejectsRetiredRoles() {
        for(var role:List.of(ValidationRole.RELATED_SELECTION,ValidationRole.COVERAGE)) {
            var roles=roles();roles.put(role,generation("gpt-6.1-terra","high"));
            assertThatThrownBy(()->enabled(roles,null)).as(role.name()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(role.name());
        }
    }
    @Test @DisplayName("미세한 lease와 유한하지 않은 예산을 거절한다") void invalidDurationsAndNumbersFailClosed() {
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofNanos(1),2,limits(),limits(),roles(),null)).isInstanceOf(IllegalArgumentException.class);
        var infinite=new Limits(10,20,Double.POSITIVE_INFINITY,1000,100,10,10);
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,infinite,limits(),roles(),null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test @DisplayName("선택 임베딩은 명시 모델과 별도 예산을 함께 요구하고 출력·모델·추론 강도를 두지 않는다") void embeddingIsOptionalButNeverImplicit() {
        var roles=roles();roles.put(ValidationRole.QUERY_EMBEDDING,new RoleBudget(2,100,null,null,null));
        assertThatThrownBy(()->enabled(roles,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->enabled(roles,"other-model")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->enabled(roles,"text-embedding-3-small")).doesNotThrowAnyException();
        assertThatThrownBy(()->enabled(roles(),"text-embedding-3-small")).isInstanceOf(IllegalArgumentException.class);
        var withOutput=roles();withOutput.put(ValidationRole.QUERY_EMBEDDING,new RoleBudget(2,100,10,null,null));
        assertThatThrownBy(()->enabled(withOutput,"text-embedding-3-small")).isInstanceOf(IllegalArgumentException.class);
    }
}
