package com.sitionix.forgeai.infrastructure.resources.completion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.lanecompletion.ApiCompletionEvidence;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadFieldContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AgentTicketPayloadType;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceCompletionPayloadContractRepositoryTest {

    private ResourceCompletionPayloadContractRepository repository;

    @BeforeEach
    void setUp() {
        this.repository = new ResourceCompletionPayloadContractRepository(new ObjectMapper());
        this.repository.init();
    }

    @Test
    void givenCompletionPayloadContracts_whenInit_thenEveryAgentPayloadContractMatchesJavaPayloadFields() {
        Arrays.stream(AgentTicketPayloadType.values())
                .forEach(payloadType -> assertThat(this.repository.findByType(payloadType.getPayloadClass()))
                        .as("completion payload contract for %s", payloadType.getPayloadClass().getSimpleName())
                        .isNotNull());
    }

    @Test
    void givenApiCompletionEvidenceContract_whenLoaded_thenNestedFieldsHaveDescriptions() {
        final CompletionPayloadObjectContract contract = this.repository.findByType(ApiCompletionEvidence.class);

        assertThat(contract.payloadType()).isEqualTo("ApiCompletionEvidence");
        assertThat(contract.fields())
                .extracting(CompletionPayloadFieldContract::name)
                .containsExactly("summary", "prUrl", "repo", "contracts");
        assertThat(contract.fields())
                .allSatisfy(field -> assertThat(field.description())
                        .as("description for ApiCompletionEvidence.%s", field.name())
                        .isNotBlank());
        assertThat(this.repository.findByTypeName("ApiCompletionContractResult").fields())
                .extracting(CompletionPayloadFieldContract::name)
                .containsExactly("scope", "method", "path", "operationId", "notes", "artifacts");
        assertThat(this.repository.findByTypeName("ApiCompletionGeneratedArtifact").fields())
                .extracting(CompletionPayloadFieldContract::name)
                .containsExactly("dependency", "role", "kind", "runId", "notes");
    }
}
