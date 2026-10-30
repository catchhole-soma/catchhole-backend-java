package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.Claim;
import org.monitoring.catchholebackend.domain.validation.service.ValidationWorkerService;
import org.monitoring.catchholebackend.domain.validation.type.ValidationRole;
import org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties",properties="chat.retrieval-embedding-version=synthetic-existing-index-v7")
class ValidationQueryIndexVersionIntegrationTest extends ValidationRecheckFixture {
    @MockitoSpyBean ValidationExecutionProperties properties;
    @Autowired ValidationWorkerService workers;
    @Test void snapshotUsesExistingExplicitIndexVersionOverride() throws Exception {
        var roles=new EnumMap<ValidationRole,ValidationExecutionProperties.RoleBudget>(properties.roles());
        roles.put(ValidationRole.QUERY_EMBEDDING,new ValidationExecutionProperties.RoleBudget(1,1000));
        when(properties.roles()).thenReturn(roles);when(properties.queryEmbeddingModel()).thenReturn("text-embedding-3-small");
        start();var claim=workers.claimValidationJob(new Claim(1,"synthetic-index-version",Set.of("VALIDATION_EXECUTION_V1")));
        assertThat(json.valueToTree(claim).at("/models/QUERY_EMBEDDING/version").asText()).isEqualTo("synthetic-existing-index-v7");
        assertThat(json.valueToTree(claim).at("/models/QUERY_EMBEDDING/dimensions").asInt()).isEqualTo(1536);noExtraction();
    }
}
