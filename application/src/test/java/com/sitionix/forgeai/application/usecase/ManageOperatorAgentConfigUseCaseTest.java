package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AgentTicketPayloadType;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageOperatorAgentConfigUseCaseTest {

    private static final String INSTRUCTION_REF = "shared/common-rules.md";

    @TempDir
    private Path repositoryRoot;

    private String originalUserDir;

    private ManageOperatorAgentConfig useCase;

    @Mock
    private AgentPropertiesProvider agentPropertiesProvider;
    @Mock
    private LaneStrategyRepository laneStrategyRepository;
    @Mock
    private InstructionRepository instructionRepository;
    @Mock
    private CompletionPayloadContractRepository completionPayloadContractRepository;

    @BeforeEach
    void setUp() throws Exception {
        this.originalUserDir = System.getProperty("user.dir");
        this.createRepositoryFiles();
        System.setProperty("user.dir", this.repositoryRoot.toString());
        this.useCase = new ManageOperatorAgentConfigUseCase(
                this.agentPropertiesProvider,
                this.laneStrategyRepository,
                this.instructionRepository,
                this.completionPayloadContractRepository,
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", this.originalUserDir);
    }

    @Test
    void givenConfiguredAgentResources_whenConfig_thenReturnAgentsStrategiesContractsAndEditableResources() {
        final AgentPropertiesProvider.AgentConfigView analyzer = this.agent(
                "analyzer",
                ScopeMode.PER_SCOPE,
                Set.of(ServiceGroup.BACKEND),
                List.of(),
                List.of(Agent.ARCHITECT)
        );
        when(analyzer.getInputPayloadTypes()).thenReturn(Map.of(Agent.ARCHITECT, AgentTicketPayloadType.ARCHITECT));
        when(analyzer.getCompletionReportPayloadType()).thenReturn(Optional.of(AgentTicketPayloadType.ARCHITECT));
        when(this.agentPropertiesProvider.getAgents()).thenReturn(List.of(analyzer));
        when(this.laneStrategyRepository.findByAgentId("analyzer")).thenReturn(LaneStrategy.builder()
                .agentId("analyzer")
                .version(1)
                .sessionMode("reuse")
                .steps(List.of(LaneStrategyStep.builder()
                        .id("completion")
                        .title("Completion")
                        .order(1)
                        .taskPlaceholder("TASK")
                        .completionContractPlaceholder("CONTRACT")
                        .instructionRefs(List.of(INSTRUCTION_REF))
                        .build()))
                .build());
        when(this.instructionRepository.findSharedInstructionRefs()).thenReturn(Set.of(INSTRUCTION_REF));
        when(this.instructionRepository.findInstructionTextByRef(INSTRUCTION_REF)).thenReturn("shared instruction");
        when(this.completionPayloadContractRepository.findByTypeName("ArchitectPayload")).thenReturn(new CompletionPayloadObjectContract(
                "ArchitectPayload",
                "Architect task input.",
                List.of()
        ));

        final ManageOperatorAgentConfig.OperatorAgentConfigResponse actual = this.useCase.config();

        assertThat(actual.agents()).singleElement()
                .satisfies(agent -> {
                    assertThat(agent.id()).isEqualTo("analyzer");
                    assertThat(agent.scopeMode()).isEqualTo("per_scope");
                    assertThat(agent.dependsOn()).isEmpty();
                    assertThat(agent.produces()).containsExactly("architect");
                    assertThat(agent.inputPayloads()).singleElement()
                            .satisfies(payload -> {
                                assertThat(payload.sourceAgent()).isEqualTo("architect");
                                assertThat(payload.payloadType()).isEqualTo("architect");
                                assertThat(payload.payloadClass()).isEqualTo("ArchitectPayload");
                            });
                    assertThat(agent.laneStrategy().steps()).singleElement()
                            .satisfies(step -> {
                                assertThat(step.id()).isEqualTo("completion");
                                assertThat(step.instructionRefs()).containsExactly(INSTRUCTION_REF);
                            });
                    assertThat(agent.payloadContracts()).singleElement()
                            .satisfies(contract -> {
                                assertThat(contract.payloadType()).isEqualTo("ArchitectPayload");
                                assertThat(contract.resourceKey()).isEqualTo("contract:ArchitectPayload");
                            });
                });
        assertThat(actual.editableResources()).extracting(ManageOperatorAgentConfig.OperatorConfigResourceView::resourceKey)
                .contains("agent-yml", "lane-strategies-yml", "instruction:" + INSTRUCTION_REF, "contract:ArchitectPayload");
        assertThat(actual.restartRequiredMessage()).contains("Restart Forge AI");
    }

    @Test
    void givenValidYamlResource_whenSaveResource_thenWriteAllowlistedSourceResource() throws Exception {
        final ManageOperatorAgentConfig.OperatorConfigResourceView actual = this.useCase.saveResource(
                new ManageOperatorAgentConfig.OperatorConfigResourceSaveRequest("agent-yml", "agents: []\n")
        );

        assertThat(actual.resourceKey()).isEqualTo("agent-yml");
        assertThat(Files.readString(this.repositoryRoot.resolve("boot/src/main/resources/agent.yml"), StandardCharsets.UTF_8))
                .isEqualTo("agents: []\n");
    }

    @Test
    void givenInstructionPathTraversal_whenSaveResource_thenRejectResourceKey() {
        assertThatThrownBy(() -> this.useCase.saveResource(
                new ManageOperatorAgentConfig.OperatorConfigResourceSaveRequest("instruction:../agent.yml", "content")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported instruction ref");
    }

    @Test
    void givenInvalidJsonContract_whenSaveResource_thenRejectContent() {
        assertThatThrownBy(() -> this.useCase.saveResource(
                new ManageOperatorAgentConfig.OperatorConfigResourceSaveRequest("contract:ArchitectPayload", "{broken")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON content");
    }

    private AgentPropertiesProvider.AgentConfigView agent(final String id,
                                                          final ScopeMode scopeMode,
                                                          final Set<ServiceGroup> groups,
                                                          final List<Agent> dependsOn,
                                                          final List<Agent> produces) {
        final AgentPropertiesProvider.AgentConfigView agent = mock(AgentPropertiesProvider.AgentConfigView.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getScopeMode()).thenReturn(scopeMode);
        when(agent.getGroups()).thenReturn(groups);
        when(agent.getDependsOn()).thenReturn(dependsOn);
        when(agent.getProduces()).thenReturn(produces);
        when(agent.isEnabled()).thenReturn(true);
        return agent;
    }

    private void createRepositoryFiles() throws Exception {
        Files.writeString(this.repositoryRoot.resolve("pom.xml"), "<project />", StandardCharsets.UTF_8);
        Files.createDirectories(this.repositoryRoot.resolve("boot/src/main/resources"));
        Files.writeString(this.repositoryRoot.resolve("boot/src/main/resources/agent.yml"), "agents: []\n", StandardCharsets.UTF_8);
        Files.writeString(this.repositoryRoot.resolve("boot/src/main/resources/lane-strategies.yml"), "strategies: []\n", StandardCharsets.UTF_8);
        Files.createDirectories(this.repositoryRoot.resolve("infrastructure/resources/src/main/resources/instructions/shared"));
        Files.writeString(
                this.repositoryRoot.resolve("infrastructure/resources/src/main/resources/instructions/" + INSTRUCTION_REF),
                "shared instruction",
                StandardCharsets.UTF_8
        );
        Files.createDirectories(this.repositoryRoot.resolve("infrastructure/resources/src/main/resources/completion-payload-contracts"));
        Files.writeString(
                this.repositoryRoot.resolve("infrastructure/resources/src/main/resources/completion-payload-contracts/ArchitectPayload.json"),
                "{\"payloadType\":\"ArchitectPayload\",\"description\":\"Architect task input.\",\"fields\":[]}",
                StandardCharsets.UTF_8
        );
    }
}
