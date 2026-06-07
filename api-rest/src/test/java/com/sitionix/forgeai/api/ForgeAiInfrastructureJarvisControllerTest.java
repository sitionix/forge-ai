package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.domain.model.jarvis.JarvisActionView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsSummaryView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisExecutionView;
import com.sitionix.forgeai.domain.exception.JarvisGatewayException;
import com.sitionix.forgeai.domain.model.jarvis.JarvisGatewayErrorCode;
import com.sitionix.forgeai.domain.model.jarvis.JarvisIntentView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisModelView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisRuntimeView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisStatusView;
import com.sitionix.forgeai.domain.usecase.ManageJarvisInfrastructure;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

class ForgeAiInfrastructureJarvisControllerTest {

    private final ManageJarvisInfrastructure manageJarvisInfrastructure = mock(ManageJarvisInfrastructure.class);
    private final ForgeAiInfrastructureJarvisController controller =
            new ForgeAiInfrastructureJarvisController(this.manageJarvisInfrastructure);

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
        when(this.manageJarvisInfrastructure.status()).thenReturn(status);

        final var response = this.controller.status();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(status);
    }

    @Test
    void actionsDoNotExposeCommandArrays() {
        when(this.manageJarvisInfrastructure.actions()).thenReturn(new JarvisActionsView(List.of(
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
        when(this.manageJarvisInfrastructure.command(request)).thenReturn(result);

        final var response = this.controller.command(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(result);
        verify(this.manageJarvisInfrastructure).command(request);
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

    @ParameterizedTest
    @EnumSource(JarvisGatewayErrorCode.class)
    void jarvisGatewayErrorsUseControlledJsonShape(final JarvisGatewayErrorCode code) {
        final JarvisGatewayException exception = new JarvisGatewayException(code, "controlled message");

        final var response = this.controller.handleJarvisGatewayException(exception);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code.name());
        assertThat(response.getBody().message()).isEqualTo("controlled message");
    }
}
