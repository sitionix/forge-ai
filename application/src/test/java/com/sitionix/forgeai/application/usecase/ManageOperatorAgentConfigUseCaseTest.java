package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.operator.OperatorConfigResource;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AgentTicketPayloadType;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import com.sitionix.forgeai.domain.repository.OperatorConfigResourceRepository;
import com.sitionix.forgeai.domain.usecase.ManageOperatorAgentConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageOperatorAgentConfigUseCaseTest {

    private static final String INSTRUCTION_REF = "shared/common-rules.md";

    private ManageOperatorAgentConfig useCase;
    private FakeOperatorConfigResourceRepository operatorConfigResourceRepository;

    @Mock
    private AgentPropertiesProvider agentPropertiesProvider;
    @Mock
    private LaneStrategyRepository laneStrategyRepository;
    @Mock
    private InstructionRepository instructionRepository;
    @Mock
    private CompletionPayloadContractRepository completionPayloadContractRepository;

    @BeforeEach
    void setUp() {
        this.operatorConfigResourceRepository = new FakeOperatorConfigResourceRepository();
        this.useCase = new ManageOperatorAgentConfigUseCase(
                this.agentPropertiesProvider,
                this.laneStrategyRepository,
                this.instructionRepository,
                this.completionPayloadContractRepository,
                this.operatorConfigResourceRepository
        );
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
        assertThat(actual.content()).isEqualTo("agents: []\n");
        assertThat(this.operatorConfigResourceRepository.agentYaml().content()).isEqualTo("agents: []\n");
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

    private static final class FakeOperatorConfigResourceRepository implements OperatorConfigResourceRepository {

        private final Map<String, String> content = new java.util.LinkedHashMap<>(Map.of(
                "agent-yml", "agents: []\n",
                "lane-strategies-yml", "strategies: []\n",
                "instruction:" + INSTRUCTION_REF, "shared instruction",
                "contract:ArchitectPayload", "{\"payloadType\":\"ArchitectPayload\",\"description\":\"Architect task input.\",\"fields\":[]}"
        ));

        @Override
        public OperatorConfigResource agentYaml() {
            return this.resource("agent-yml", "agent.yml", "yaml");
        }

        @Override
        public OperatorConfigResource laneStrategiesYaml() {
            return this.resource("lane-strategies-yml", "lane-strategies.yml", "yaml");
        }

        @Override
        public OperatorConfigResource instruction(final String instructionRef) {
            return this.resource("instruction:" + instructionRef, instructionRef, "markdown");
        }

        @Override
        public OperatorConfigResource contract(final String payloadType) {
            return this.resource("contract:" + payloadType, payloadType, "json");
        }

        @Override
        public List<OperatorConfigResource> contracts() {
            return List.of(this.contract("ArchitectPayload"));
        }

        @Override
        public OperatorConfigResource save(final String resourceKey, final String content) {
            this.content.put(resourceKey, content);
            if ("agent-yml".equals(resourceKey)) {
                return this.agentYaml();
            }
            if ("lane-strategies-yml".equals(resourceKey)) {
                return this.laneStrategiesYaml();
            }
            if (resourceKey.startsWith("instruction:")) {
                return this.instruction(resourceKey.substring("instruction:".length()));
            }
            if (resourceKey.startsWith("contract:")) {
                return this.contract(resourceKey.substring("contract:".length()));
            }
            throw new IllegalArgumentException("Unsupported config resourceKey: " + resourceKey);
        }

        private OperatorConfigResource resource(final String resourceKey, final String label, final String type) {
            return OperatorConfigResource.builder()
                    .resourceKey(resourceKey)
                    .label(label)
                    .resourceType(type)
                    .path("/repo/" + label)
                    .writable(true)
                    .content(this.content.get(resourceKey))
                    .build();
        }
    }
}
