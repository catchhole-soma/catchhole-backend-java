package org.monitoring.catchholebackend.global.config.validation;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.validation.type.ValidationRole;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.*;

@DisplayName("검수 실행의 명시적 설정과 기본 비활성")
class ValidationExecutionPropertiesTest {
    Limits limits() {return new Limits(10,20,30,1000,100,100,10,100,10);}
    Map<ValidationRole,RoleBudget> roles() {var roles=new EnumMap<ValidationRole,RoleBudget>(ValidationRole.class);for(var r:ValidationRole.values())if(r!=ValidationRole.QUERY_EMBEDDING)roles.put(r,new RoleBudget(10,10000));return roles;}
    @Test @DisplayName("비활성 앱은 검수 예산 없이 기동한다") void disabledNeedsNoExecutionConfiguration() {
        assertThatCode(()->new ValidationExecutionProperties(false,null,null,null,null,null,null)).doesNotThrowAnyException();
    }
    @Test @DisplayName("활성 앱은 빠진 제한이나 역할 예산을 거절한다") void enabledRequiresAllExplicitLimits() {
        assertThatThrownBy(()->new ValidationExecutionProperties(true,null,null,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        var roles=roles();roles.remove(ValidationRole.RELATED_SELECTION);
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,limits(),limits(),roles,null)).isInstanceOf(IllegalArgumentException.class);
        var unsetContext=new Limits(10,20,30,1000,100,100,null,100,10);
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,unsetContext,limits(),roles(),null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test @DisplayName("미세한 lease와 유한하지 않은 예산을 거절한다") void invalidDurationsAndNumbersFailClosed() {
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofNanos(1),2,limits(),limits(),roles(),null)).isInstanceOf(IllegalArgumentException.class);
        var infinite=new Limits(10,20,Double.POSITIVE_INFINITY,1000,100,100,10,100,10);
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,infinite,limits(),roles(),null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test @DisplayName("선택 임베딩은 명시 모델과 별도 예산을 함께 요구한다") void embeddingIsOptionalButNeverImplicit() {
        var roles=roles();roles.put(ValidationRole.QUERY_EMBEDDING,new RoleBudget(2,100));
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,limits(),limits(),roles,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,limits(),limits(),roles,"other-model")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,limits(),limits(),roles,"text-embedding-3-small")).doesNotThrowAnyException();
        assertThatThrownBy(()->new ValidationExecutionProperties(true,Duration.ofSeconds(30),2,limits(),limits(),roles(),"text-embedding-3-small")).isInstanceOf(IllegalArgumentException.class);
    }
}
