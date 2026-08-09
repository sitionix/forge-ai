package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.AgentDependencyResponse;
import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.application.usecase.CreateProjectCommand;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDependencySummary;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Project;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForgeAgentApiMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DEPENDENCY_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant CREATED = Instant.parse("2026-08-04T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-04T00:01:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ForgeAgentApiMapper mapper = new ForgeAgentApiMapper(this.objectMapper);

    @Test
    void mapsProjectRequestToCommand() {
        final var request = new CreateProjectRequest("Sitionix");
        final var expected = new CreateProjectCommand("Sitionix");

        final var actual = this.mapper.toCommand(request);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsAgentRequestToCommand() throws Exception {
        final SaveAgentRequest request = new SaveAgentRequest(
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of(DEPENDENCY_ID)
        );
        final var expected = new SaveAgentCommand(
                "Analyzer",
                "Analyze changes.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                List.of(DEPENDENCY_ID)
        );

        final var actual = this.mapper.toCommand(request);

        assertThat(actual).isEqualTo(expected);
    }

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
    void mapsProjectToResponse() {
        final var project = new Project(PROJECT_ID, "Sitionix", "sitionix", CREATED, UPDATED);
        final var expected = new ProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED);

        final var actual = this.mapper.toResponse(project);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsAgentListItemToResponse() {
        final var item = new AgentListItem(
                AGENT_ID,
                PROJECT_ID,
                "Analyzer",
                List.of(new AgentDependencySummary(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );
        final var expected = new AgentListResponse(
                AGENT_ID,
                PROJECT_ID,
                "Analyzer",
                List.of(new AgentDependencyResponse(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );

        final var actual = this.mapper.toResponse(item);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsAgentDetailsToResponse() throws Exception {
        final var agent = new AgentDetails(
                AGENT_ID,
                PROJECT_ID,
                "Analyzer",
                "Analyze changes.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                List.of(new AgentDependencySummary(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );
        final var expected = new AgentResponse(
                AGENT_ID,
                PROJECT_ID,
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                List.of(new AgentDependencyResponse(DEPENDENCY_ID, "Architect")),
                CREATED,
                UPDATED
        );

        final var actual = this.mapper.toResponse(agent);

        assertThat(actual).isEqualTo(expected);
    }
}
