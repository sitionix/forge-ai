package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForgeAgentApiMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ForgeAgentApiMapper mapper = new ForgeAgentApiMapper(this.objectMapper);

    @Test
    void rejectsOutputSchemaWithNonObjectRoot() throws Exception {
        final SaveAgentRequest request = new SaveAgentRequest(
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("[]"),
                List.of()
        );

        assertThatThrownBy(() -> this.mapper.toCommand(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Output schema must be a JSON object.");
    }

    @Test
    void acceptsOutputSchemaWithObjectRoot() throws Exception {
        final SaveAgentRequest request = new SaveAgentRequest(
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of()
        );

        assertThat(this.mapper.toCommand(request).outputSchema().jsonObject()).isEqualTo("{\"type\":\"object\"}");
    }
}
