package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.exception.JarvisGatewayException;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsSummaryView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisChatContextView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisChatRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisChatResponse;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisExecutionView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisGatewayErrorCode;
import com.sitionix.forgeai.domain.model.jarvis.JarvisIntentView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisModelView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisRuntimeView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisStatusView;
import com.sitionix.forgeai.domain.port.JarvisGateway;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class JarvisInfrastructureControllerIT extends AbstractForgeAiIT {

    private static final String COMMAND_TEXT = "check ollama";
    private static final String CHAT_MESSAGE = "explain how JarvisGateway works";
    private static final String CHAT_ERROR_MESSAGE = "explain JarvisGateway";

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

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisStatus())
                .assertDefault();

        verify(this.jarvisGateway).status();
    }

    @Test
    @DisplayName("Should expose allowlisted Jarvis action metadata without raw commands")
    void givenJarvisGatewayActions_whenGetActions_thenReturnSafeActionMetadata() throws Exception {
        when(this.jarvisGateway.actions()).thenReturn(new JarvisActionsView(List.of(
                new JarvisActionView("ollama_status", "Check Ollama local API", List.of("health"))
        )));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisActions())
                .assertDefault();

        verify(this.jarvisGateway).actions();
    }

    @Test
    @DisplayName("Should delegate command text through application use case to Jarvis gateway")
    void givenValidCommand_whenPostCommand_thenReturnJarvisExecutionResult() throws Exception {
        final JarvisCommandRequest request = new JarvisCommandRequest(COMMAND_TEXT);
        when(this.jarvisGateway.command(request)).thenReturn(new JarvisCommandResultView(
                COMMAND_TEXT,
                new JarvisIntentView("ollama_status", "health", Map.of()),
                new JarvisExecutionView(true, "Action executed: ollama_status.health", "Ollama is reachable")
        ));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisCommand())
                .assertDefault();

        verify(this.jarvisGateway).command(request);
    }

    @Test
    @DisplayName("Should reject blank commands before Jarvis gateway")
    void givenBlankCommand_whenPostCommand_thenReturnControlledBadRequest() throws Exception {
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisCommand())
                .withRequest("requestJarvisCommandBlank.json")
                .expectResponse("responseJarvisCommandBlank.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .assertDefault();

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

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisCommand())
                .withRequest("requestJarvisCommandUnsupported.json")
                .expectResponse("responseJarvisCommandUnsupported.json")
                .expectStatus(HttpStatus.FORBIDDEN)
                .assertDefault();

        verify(this.jarvisGateway).command(request);
    }

    @Test
    @DisplayName("Should proxy Jarvis chat request through application use case to Jarvis gateway")
    void givenValidChat_whenPostChat_thenReturnJarvisChatResponse() throws Exception {
        final JarvisChatRequest request = new JarvisChatRequest(CHAT_MESSAGE, 12000);
        when(this.jarvisGateway.chat(request)).thenReturn(new JarvisChatResponse(
                "JarvisGateway proxies Forge requests to Jarvis.",
                List.of(new JarvisChatContextView(
                        "forge-ai",
                        "Forge AI Service SOX",
                        "application/src/main/java/JarvisGateway.java",
                        1,
                        40,
                        "Matched JarvisGateway",
                        1.0
                )),
                List.of()
        ));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisChat())
                .assertDefault();

        verify(this.jarvisGateway).chat(request);
    }

    @Test
    @DisplayName("Should reject blank chat messages before Jarvis gateway")
    void givenBlankChatMessage_whenPostChat_thenReturnControlledBadRequest() throws Exception {
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisChat())
                .withRequest("requestJarvisChatBlank.json")
                .expectResponse("responseJarvisChatBlank.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .assertDefault();

        verify(this.jarvisGateway, never()).chat(any());
    }

    @Test
    @DisplayName("Should map Jarvis unavailable during chat to controlled error")
    void givenJarvisUnavailable_whenPostChat_thenReturnControlledServiceUnavailable() throws Exception {
        final JarvisChatRequest request = new JarvisChatRequest(CHAT_ERROR_MESSAGE, 12000);
        when(this.jarvisGateway.chat(request)).thenThrow(new JarvisGatewayException(
                JarvisGatewayErrorCode.JARVIS_UNAVAILABLE,
                "Jarvis is unavailable"
        ));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisChat())
                .withRequest("requestJarvisChatUnavailable.json")
                .expectResponse("responseJarvisChatUnavailable.json")
                .expectStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .assertDefault();

        verify(this.jarvisGateway).chat(request);
    }

    @Test
    @DisplayName("Should map invalid Jarvis chat response to controlled error")
    void givenInvalidJarvisChatResponse_whenPostChat_thenReturnControlledBadGateway() throws Exception {
        final JarvisChatRequest request = new JarvisChatRequest(CHAT_ERROR_MESSAGE, 12000);
        when(this.jarvisGateway.chat(request)).thenThrow(new JarvisGatewayException(
                JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE,
                "Jarvis chat response is invalid"
        ));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisChat())
                .withRequest("requestJarvisChatBadResponse.json")
                .expectResponse("responseJarvisChatBadResponse.json")
                .expectStatus(HttpStatus.BAD_GATEWAY)
                .assertDefault();

        verify(this.jarvisGateway).chat(request);
    }

    @Test
    @DisplayName("Should map Jarvis chat timeout to controlled error")
    void givenJarvisTimeout_whenPostChat_thenReturnControlledGatewayTimeout() throws Exception {
        final JarvisChatRequest request = new JarvisChatRequest(CHAT_ERROR_MESSAGE, 12000);
        when(this.jarvisGateway.chat(request)).thenThrow(new JarvisGatewayException(
                JarvisGatewayErrorCode.JARVIS_TIMEOUT,
                "Jarvis request timed out"
        ));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.jarvisChat())
                .withRequest("requestJarvisChatTimeout.json")
                .expectResponse("responseJarvisChatTimeout.json")
                .expectStatus(HttpStatus.GATEWAY_TIMEOUT)
                .assertDefault();

        verify(this.jarvisGateway).chat(request);
    }
}
