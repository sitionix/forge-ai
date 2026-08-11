package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.CodexRuntimeEffort;
import com.sitionix.forgeagent.domain.model.CodexRuntimeModel;
import com.sitionix.forgeagent.domain.model.CodexRuntimeProvider;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.RuntimeProviderStatus;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.CodexRuntimePort;
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

    @Mock
    private CodexRuntimePort codexRuntimePort;

    private AgentUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new AgentUseCases(this.projectRepository, this.agentDefinitionRepository, this.codexRuntimePort, CLOCK);
    }

    @Test
    void createsAgentDefinition() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.agentDefinitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final AgentDetails created = this.useCases.createAgent(
                this.projectId,
                new SaveAgentCommand(" Analyzer ", "Instructions", OUTPUT_SCHEMA)
        );

        assertThat(created.name()).isEqualTo("Analyzer");
        assertThat(created.projectId()).isEqualTo(this.projectId);
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
    void createsAgentWithValidatedModelSelection() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.codexRuntimePort.getModels()).thenReturn(this.readyRuntime());
        when(this.agentDefinitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final AgentModelSelection selection = new AgentModelSelection("codex", "discovered-model", "medium");
        final AgentDetails created = this.useCases.createAgent(
                this.projectId,
                new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA, selection)
        );

        assertThat(created.model()).isEqualTo(selection);
    }

    @Test
    void rejectsUnknownModelSelection() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.codexRuntimePort.getModels()).thenReturn(this.readyRuntime());

        assertThatThrownBy(() -> this.useCases.createAgent(
                this.projectId,
                new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA, new AgentModelSelection("codex", "missing", "medium"))
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("UNKNOWN_AGENT_MODEL");
    }

    @Test
    void rejectsUnsupportedEffortSelection() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.codexRuntimePort.getModels()).thenReturn(this.readyRuntime());

        assertThatThrownBy(() -> this.useCases.createAgent(
                this.projectId,
                new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA, new AgentModelSelection("codex", "discovered-model", "high"))
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_AGENT_MODEL_EFFORT");
    }

    @Test
    void rejectsUnavailableRuntimeForModelSelection() {
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.codexRuntimePort.getModels()).thenReturn(new CodexRuntimeProvider("codex", "Codex", RuntimeProviderStatus.UNAVAILABLE, null, List.of()));

        assertThatThrownBy(() -> this.useCases.createAgent(
                this.projectId,
                new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA, new AgentModelSelection("codex", "discovered-model", "medium"))
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("AGENT_MODEL_PROVIDER_UNAVAILABLE");
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
    void listAndGetAgentDefinitionData() {
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

    private CodexRuntimeProvider readyRuntime() {
        return new CodexRuntimeProvider(
                "codex",
                "Codex",
                RuntimeProviderStatus.READY,
                "codex 1.0.0",
                List.of(new CodexRuntimeModel(
                        "discovered-model",
                        "Discovered Model",
                        "Live model",
                        List.of(new CodexRuntimeEffort("medium", "Medium"))
                ))
        );
    }
}
