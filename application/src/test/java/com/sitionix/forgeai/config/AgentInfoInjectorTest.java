package com.sitionix.forgeai.config;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.props.AgentConfigView;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
        this.executorsByBeanName = new LinkedHashMap<>();
        this.executorsByBeanName.put("analyzeAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("architectAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("apiAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("eventAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("qaLeadAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("beAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("feAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("testUnitAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("testItAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("testUiAgentExecutor", mock(ExecuteAgent.class));
        this.executorsByBeanName.put("reviewerAgentExecutor", mock(ExecuteAgent.class));
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
        final List<AgentConfigView> configs = Arrays.stream(Agent.values())
                .map(agent -> {
                    final AgentConfigView view = mock(AgentConfigView.class);
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
