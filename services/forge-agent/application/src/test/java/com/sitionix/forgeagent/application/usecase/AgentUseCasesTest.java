package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentUseCasesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentOutputSchema OUTPUT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{}");

    private final UUID projectId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID agentId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AgentDefinitionRepository agentDefinitionRepository;

    private AgentUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new AgentUseCases(this.projectRepository, this.agentDefinitionRepository, CLOCK);
    }

    @Test
    void createsAgentWithoutDependencyGraphLocking() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.agentDefinitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final AgentDetails created = this.useCases.createAgent(
                this.projectId,
                new SaveAgentCommand(" Analyzer ", "Instructions", OUTPUT_SCHEMA)
        );

        assertThat(created.name()).isEqualTo("Analyzer");
        assertThat(created.projectId()).isEqualTo(this.projectId);
        verify(this.projectRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void updatesOnlyAgentDefinitionFields() {
        final AgentDefinition existing = this.agent("Analyzer");
        when(this.agentDefinitionRepository.findById(this.agentId)).thenReturn(Optional.of(existing));
        when(this.agentDefinitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final AgentDetails updated = this.useCases.updateAgent(
                this.agentId,
                new SaveAgentCommand("Analyzer Updated", "New instructions", OUTPUT_SCHEMA)
        );

        assertThat(updated.name()).isEqualTo("Analyzer Updated");
        assertThat(updated.instructions()).isEqualTo("New instructions");
        assertThat(updated.createdAt()).isEqualTo(existing.createdAt());
        assertThat(updated.updatedAt()).isEqualTo(Instant.parse("2026-08-04T00:00:00Z"));
    }

    @Test
    void rejectsBlankAgentName() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));

        assertThatThrownBy(() -> this.useCases.createAgent(this.projectId, new SaveAgentCommand(" ", "Instructions", OUTPUT_SCHEMA)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Agent name is required.");
    }

    @Test
    void rejectsDuplicateAgentNameWithinProject() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.agentDefinitionRepository.existsByProjectIdAndNormalizedName(this.projectId, "analyzer")).thenReturn(true);

        assertThatThrownBy(() -> this.useCases.createAgent(this.projectId, new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA)))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("DUPLICATE_AGENT_NAME");
    }

    @Test
    void listAndGetDoNotExposeDependencies() {
        final AgentDefinition agent = this.agent("Analyzer");
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.agentDefinitionRepository.findByProjectId(this.projectId)).thenReturn(List.of(agent));
        when(this.agentDefinitionRepository.findById(this.agentId)).thenReturn(Optional.of(agent));

        assertThat(this.useCases.listProjectAgents(this.projectId)).singleElement()
                .extracting("name")
                .isEqualTo("Analyzer");
        assertThat(this.useCases.getAgent(this.agentId).name()).isEqualTo("Analyzer");
    }

    @Test
    void missingProjectIsNotFound() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.createAgent(this.projectId, new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("PROJECT_NOT_FOUND");
    }

    private Project project() {
        return new Project(this.projectId, "Sitionix", "sitionix", Instant.parse("2026-08-04T00:00:00Z"), Instant.parse("2026-08-04T00:00:00Z"));
    }

    private AgentDefinition agent(final String name) {
        return new AgentDefinition(
                this.agentId,
                this.projectId,
                name,
                name.toLowerCase(),
                "Instructions",
                OUTPUT_SCHEMA,
                Instant.parse("2026-08-03T00:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z")
        );
    }
}
