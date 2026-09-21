package com.sitionix.forgeai.api.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.InvalidNullException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.sitionix.forgeai.api.agentproxy.NodeRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkingRepositoriesContractTest {
    private final ObjectMapper mapper;

    WorkingRepositoriesContractTest() {
        var builder = new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        new WorkingRepositoriesJacksonConfiguration().strictWorkingRepositoryBooleans().customize(builder);
        this.mapper = builder.build();
    }

    @Test
    void omittedFieldsUseLegacyDefaults() throws Exception {
        var dto = mapper.readValue("{\"scopeMode\":\"GLOBAL\"}", NodeRequest.class);
        assertThat(dto.includeTaskRepositories()).isTrue();
        assertThat(dto.workspaceRepositoryIds()).isEmpty();
    }

    @Test
    void retainsExplicitFalseAndOrderedUuidList() throws Exception {
        var dto = mapper.readValue("""
                {"includeTaskRepositories":false,
                 "workspaceRepositoryIds":["10000000-0000-4000-8000-000000000001"]}
                """, NodeRequest.class);
        assertThat(dto.includeTaskRepositories()).isFalse();
        assertThat(dto.workspaceRepositoryIds()).containsExactly(UUID.fromString("10000000-0000-4000-8000-000000000001"));
    }

    @Test
    void rejectsNullBoolean() {
        assertThatThrownBy(() -> mapper.readValue("{\"includeTaskRepositories\":null}", NodeRequest.class))
                .isInstanceOf(InvalidNullException.class).hasMessageContaining("includeTaskRepositories");
    }

    @Test
    void rejectsStringBoolean() {
        assertThatThrownBy(() -> mapper.readValue("{\"includeTaskRepositories\":\"false\"}", NodeRequest.class))
                .isInstanceOf(MismatchedInputException.class).hasMessageContaining("includeTaskRepositories");
    }

    @Test
    void rejectsNullList() {
        assertThatThrownBy(() -> mapper.readValue("{\"workspaceRepositoryIds\":null}", NodeRequest.class))
                .isInstanceOf(InvalidNullException.class).hasMessageContaining("workspaceRepositoryIds");
    }

    @Test
    void rejectsStringInsteadOfList() {
        assertThatThrownBy(() -> mapper.readValue("{\"workspaceRepositoryIds\":\"bad\"}", NodeRequest.class))
                .isInstanceOf(MismatchedInputException.class).hasMessageContaining("workspaceRepositoryIds");
    }

    @Test
    void rejectsNullListElement() {
        assertThatThrownBy(() -> mapper.readValue("{\"workspaceRepositoryIds\":[null]}", NodeRequest.class))
                .isInstanceOf(InvalidNullException.class).hasMessageContaining("workspaceRepositoryIds");
    }

    @Test
    void rejectsInvalidUuid() {
        assertThatThrownBy(() -> mapper.readValue("{\"workspaceRepositoryIds\":[\"not-a-uuid\"]}", NodeRequest.class))
                .isInstanceOf(InvalidFormatException.class).hasMessageContaining("workspaceRepositoryIds");
    }
    @Test
    void existingPrimitiveBooleanCoercionIsUnchanged() throws Exception {
        assertThat(mapper.readValue("{\"enabled\":\"false\"}", ExistingBooleanContract.class).enabled()).isFalse();
    }

    private record ExistingBooleanContract(boolean enabled) { }
}
