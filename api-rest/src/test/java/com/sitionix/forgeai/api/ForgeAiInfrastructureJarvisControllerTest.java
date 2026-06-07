package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisActionView;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisActionsSummaryView;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisActionsView;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisExecutionView;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisGateway;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisGatewayException;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisIntentView;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisModelView;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisRuntimeView;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisStatusView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ForgeAiInfrastructureJarvisControllerTest {

    private final JarvisGateway gateway = mock(JarvisGateway.class);
    private final ForgeAiInfrastructureJarvisController controller = new ForgeAiInfrastructureJarvisController(this.gateway);

    @Test
    void statusDelegatesToGateway() {
        final JarvisStatusView status = new JarvisStatusView(
                "UP",
                "127.0.0.1",
                7071,
                new JarvisModelView("qwen2.5-coder:7b"),
                new JarvisRuntimeView("http://localhost:11434", "UP"),
                new JarvisActionsSummaryView(2)
        );
        when(this.gateway.status()).thenReturn(status);

        final var response = this.controller.status();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(status);
    }

    @Test
    void actionsDoNotExposeCommandArrays() {
        when(this.gateway.actions()).thenReturn(new JarvisActionsView(List.of(
                new JarvisActionView("ollama_status", "Check Ollama local API", List.of("health"))
        )));

        final var response = this.controller.actions();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().actions()).hasSize(1);
        assertThat(response.getBody().actions().getFirst().targets()).containsExactly("health");
    }

    @Test
    void commandDelegatesTextToGateway() {
        final JarvisCommandRequest request = new JarvisCommandRequest("перевір ollama");
        final JarvisCommandResultView result = new JarvisCommandResultView(
                "перевір ollama",
                new JarvisIntentView("ollama_status", "health", Map.of()),
                new JarvisExecutionView(true, "Action executed: ollama_status.health", "Ollama is reachable")
        );
        when(this.gateway.command(request)).thenReturn(result);

        final var response = this.controller.command(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(result);
        verify(this.gateway).command(request);
    }

    @Test
    void unsupportedActionMapsToForbidden() {
        final JarvisGatewayException exception = new JarvisGatewayException(
                JarvisGatewayErrorCode.UNSUPPORTED_ACTION,
                "The requested action is not allowlisted"
        );

        final var response = this.controller.handleJarvisGatewayException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_ACTION");
    }
}
