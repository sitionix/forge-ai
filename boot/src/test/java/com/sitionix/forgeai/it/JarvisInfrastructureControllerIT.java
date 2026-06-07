package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.exception.JarvisGatewayException;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsSummaryView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisExecutionView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisGatewayErrorCode;
import com.sitionix.forgeai.domain.model.jarvis.JarvisIntentView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisModelView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisRuntimeView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisStatusView;
import com.sitionix.forgeai.domain.port.JarvisGateway;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class JarvisInfrastructureControllerIT extends AbstractForgeAiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestManager testManager;

    @MockBean
    private JarvisGateway jarvisGateway;

    @Test
    @DisplayName("Should expose Jarvis status through Forge infrastructure API")
    void givenJarvisGatewayStatus_whenGetStatus_thenReturnForgeInfrastructureStatus() throws Exception {
        when(this.jarvisGateway.status()).thenReturn(new JarvisStatusView(
                "UP",
                "127.0.0.1",
                7071,
                new JarvisModelView("qwen2.5-coder:7b"),
                new JarvisRuntimeView("http://localhost:11434", "UP"),
                new JarvisActionsSummaryView(2)
        ));

        this.mockMvc.perform(get("/api/v1/infrastructure/jarvis/status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.host").value("127.0.0.1"))
                .andExpect(jsonPath("$.port").value(7071))
                .andExpect(jsonPath("$.model.defaultModel").value("qwen2.5-coder:7b"))
                .andExpect(jsonPath("$.ollama.status").value("UP"))
                .andExpect(jsonPath("$.actions.count").value(2));
    }

    @Test
    @DisplayName("Should expose allowlisted Jarvis action metadata without raw commands")
    void givenJarvisGatewayActions_whenGetActions_thenReturnSafeActionMetadata() throws Exception {
        when(this.jarvisGateway.actions()).thenReturn(new JarvisActionsView(List.of(
                new JarvisActionView("ollama_status", "Check Ollama local API", List.of("health"))
        )));

        this.mockMvc.perform(get("/api/v1/infrastructure/jarvis/actions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].action").value("ollama_status"))
                .andExpect(jsonPath("$.actions[0].description").value("Check Ollama local API"))
                .andExpect(jsonPath("$.actions[0].targets[0]").value("health"))
                .andExpect(jsonPath("$.actions[0].command").doesNotExist());
    }

    @Test
    @DisplayName("Should delegate command text through application use case to Jarvis gateway")
    void givenValidCommand_whenPostCommand_thenReturnJarvisExecutionResult() throws Exception {
        final JarvisCommandRequest request = new JarvisCommandRequest("перевір ollama");
        when(this.jarvisGateway.command(request)).thenReturn(new JarvisCommandResultView(
                "перевір ollama",
                new JarvisIntentView("ollama_status", "health", Map.of()),
                new JarvisExecutionView(true, "Action executed: ollama_status.health", "Ollama is reachable")
        ));

        this.mockMvc.perform(post("/api/v1/infrastructure/jarvis/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"перевір ollama"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input").value("перевір ollama"))
                .andExpect(jsonPath("$.intent.action").value("ollama_status"))
                .andExpect(jsonPath("$.intent.target").value("health"))
                .andExpect(jsonPath("$.execution.executed").value(true))
                .andExpect(jsonPath("$.execution.output").value("Ollama is reachable"));

        verify(this.jarvisGateway).command(request);
    }

    @Test
    @DisplayName("Should reject blank commands before Jarvis gateway")
    void givenBlankCommand_whenPostCommand_thenReturnControlledBadRequest() throws Exception {
        this.mockMvc.perform(post("/api/v1/infrastructure/jarvis/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMMAND"))
                .andExpect(jsonPath("$.message").value("Command text must not be empty"));

        verify(this.jarvisGateway, never()).command(any());
    }

    @Test
    @DisplayName("Should return controlled JSON when Jarvis rejects unsupported action")
    void givenUnsupportedJarvisAction_whenPostCommand_thenReturnControlledForbidden() throws Exception {
        final JarvisCommandRequest request = new JarvisCommandRequest("hi");
        when(this.jarvisGateway.command(request)).thenThrow(new JarvisGatewayException(
                JarvisGatewayErrorCode.UNSUPPORTED_ACTION,
                "The requested action is not allowlisted"
        ));

        this.mockMvc.perform(post("/api/v1/infrastructure/jarvis/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"hi"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_ACTION"))
                .andExpect(jsonPath("$.message").value("The requested action is not allowlisted"));
    }
}
