package org.monitoring.catchholebackend.support;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/** 커밋된 fixture를 사용하는 통합 테스트의 Context 캐시를 테스트 클래스별로 분리한다. */
public class TestClassContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new TestClassContextCustomizer(testClass);
    }

    private record TestClassContextCustomizer(Class<?> testClass) implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            // record의 equals/hashCode가 캐시 키에 클래스를 포함한다.
            // DB URL은 기존 test 설정과 PostgreSQL 테스트의 명시적인 override를 그대로 따른다.
        }
    }
}
