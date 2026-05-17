package com.sitionix.forgeai.config;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentInfoInjectorTest {

    private AgentInfoInjector agentInfoInjector;

    @Mock
    private AgentPropertiesProvider agentPropertiesProvider;

    private Map<String, ExecuteAgent> executorsByBeanName;

    @BeforeEach
    void setUp() {
        this.executorsByBeanName = Map.of(
                "analyzeAgentExecutor", mock(ExecuteAgent.class),
                "architectAgentExecutor", mock(ExecuteAgent.class),
                "apiAgentExecutor", mock(ExecuteAgent.class),
                "eventAgentExecutor", mock(ExecuteAgent.class),
                "qaLeadAgentExecutor", mock(ExecuteAgent.class),
                "beAgentExecutor", mock(ExecuteAgent.class),
                "feAgentExecutor", mock(ExecuteAgent.class),
                "testUnitAgentExecutor", mock(ExecuteAgent.class),
                "testItAgentExecutor", mock(ExecuteAgent.class),
                "testUiAgentExecutor", mock(ExecuteAgent.class)
        );
        this.agentInfoInjector = new AgentInfoInjector(this.agentPropertiesProvider, this.executorsByBeanName);
    }

    @AfterEach
    void tearDown() {
        for (final Agent agent : Agent.values()) {
            agent.setInfo(null);
            agent.setExecutor(null);
        }
        verifyNoMoreInteractions(this.agentPropertiesProvider);
    }

    @Test
    void givenAgentConfigs_whenInjectInfo_thenBindInfoAndExecutorsToAllAgents() {
        //given
        final List<AgentPropertiesProvider.AgentConfigView> configs = Arrays.stream(Agent.values())
                .map(agent -> {
                    final AgentPropertiesProvider.AgentConfigView view = mock(AgentPropertiesProvider.AgentConfigView.class);
                    when(view.getId()).thenReturn(agent.getId());
                    return view;
                })
                .toList();
        when(this.agentPropertiesProvider.getAgents()).thenReturn(configs);

        //when
        this.agentInfoInjector.injectInfo();

        //then
        for (final Agent agent : Agent.values()) {
            assertThat(agent.getInfo()).isNotNull();
            assertThat(agent.getExecutor()).isEqualTo(this.executorsByBeanName.get(agent.getExecutorBeanName()));
        }

        verify(this.agentPropertiesProvider, times(2)).getAgents();
    }

    @Test
    void givenNullAgentConfigs_whenInjectInfo_thenThrowIllegalStateException() {
        //given
        when(this.agentPropertiesProvider.getAgents()).thenReturn(null);

        //when then
        assertThatThrownBy(() -> this.agentInfoInjector.injectInfo())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No agents configured in agent.yml");

        verify(this.agentPropertiesProvider).getAgents();
    }
}
