package com.sitionix.forgeai.api.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class WorkingRepositoriesContractTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void retainsExplicitWorkspaceSelection() throws Exception {
        var request = mapper.readValue("""
                {"scopeMode":"GLOBAL","includeTaskRepositories":false,
                 "workspaceRepositoryIds":["10000000-0000-4000-8000-000000000001"]}
                """, NodeRequest.class);
        var json = mapper.valueToTree(request);
        assertThat(json.path("includeTaskRepositories").asBoolean(true)).isFalse();
        assertThat(json.path("workspaceRepositoryIds").get(0).asText())
                .isEqualTo("10000000-0000-4000-8000-000000000001");
    }

    @Test
    void omittedFieldsUseLegacyDefaults() throws Exception {
        var request = mapper.readValue("{\"scopeMode\":\"GLOBAL\"}", NodeRequest.class);
        var json = mapper.valueToTree(request);
        assertThat(json.path("includeTaskRepositories").asBoolean(false)).isTrue();
        assertThat(json.path("workspaceRepositoryIds").isArray()).isTrue();
        assertThat(json.path("workspaceRepositoryIds").size()).isZero();
    }

    @Test
    void rejectsExplicitInvalidValues() {
        for (String field : new String[]{"\"includeTaskRepositories\":null",
                "\"includeTaskRepositories\":\"false\"", "\"workspaceRepositoryIds\":null",
                "\"workspaceRepositoryIds\":[null]", "\"workspaceRepositoryIds\":\"bad\""}) {
            assertThatThrownBy(() -> mapper.readValue("{" + field + "}", NodeRequest.class))
                    .as(field).isInstanceOf(Exception.class);
        }
    }
}
