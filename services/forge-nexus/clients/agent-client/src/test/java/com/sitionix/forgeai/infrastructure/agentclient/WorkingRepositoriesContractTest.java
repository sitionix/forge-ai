package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.InvalidNullException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkingRepositoriesContractTest {
    private final ObjectMapper mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .withCoercionConfig(Boolean.class, config -> config
                    .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.String, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                    .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.EmptyString, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                    .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail))
            .build();

    @Test
    void omittedFieldsUseLegacyDefaults() throws Exception {
        var dto = mapper.readValue("{\"scopeMode\":\"GLOBAL\"}", NodeResponse.class);
        assertThat(dto.includeTaskRepositories()).isTrue();
        assertThat(dto.workspaceRepositoryIds()).isEmpty();
    }

    @Test
    void retainsExplicitFalseAndOrderedUuidList() throws Exception {
        var dto = mapper.readValue("""
                {"includeTaskRepositories":false,
                 "workspaceRepositoryIds":["10000000-0000-4000-8000-000000000001"]}
                """, NodeResponse.class);
        assertThat(dto.includeTaskRepositories()).isFalse();
        assertThat(dto.workspaceRepositoryIds()).containsExactly(UUID.fromString("10000000-0000-4000-8000-000000000001"));
    }

    @Test
    void rejectsNullBoolean() {
        assertThatThrownBy(() -> mapper.readValue("{\"includeTaskRepositories\":null}", NodeResponse.class))
                .isInstanceOf(InvalidNullException.class).hasMessageContaining("includeTaskRepositories");
    }

    @Test
    void rejectsStringBoolean() {
        assertThatThrownBy(() -> mapper.readValue("{\"includeTaskRepositories\":\"false\"}", NodeResponse.class))
                .isInstanceOf(MismatchedInputException.class).hasMessageContaining("includeTaskRepositories");
    }

    @Test
    void rejectsNullList() {
        assertThatThrownBy(() -> mapper.readValue("{\"workspaceRepositoryIds\":null}", NodeResponse.class))
                .isInstanceOf(InvalidNullException.class).hasMessageContaining("workspaceRepositoryIds");
    }

    @Test
    void rejectsStringInsteadOfList() {
        assertThatThrownBy(() -> mapper.readValue("{\"workspaceRepositoryIds\":\"bad\"}", NodeResponse.class))
                .isInstanceOf(MismatchedInputException.class).hasMessageContaining("workspaceRepositoryIds");
    }

    @Test
    void rejectsNullListElement() {
        assertThatThrownBy(() -> mapper.readValue("{\"workspaceRepositoryIds\":[null]}", NodeResponse.class))
                .isInstanceOf(InvalidNullException.class).hasMessageContaining("workspaceRepositoryIds");
    }

    @Test
    void rejectsInvalidUuid() {
        assertThatThrownBy(() -> mapper.readValue("{\"workspaceRepositoryIds\":[\"not-a-uuid\"]}", NodeResponse.class))
                .isInstanceOf(InvalidFormatException.class).hasMessageContaining("workspaceRepositoryIds");
    }
}
