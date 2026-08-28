package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import jakarta.validation.Valid;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ForgeAgentExceptionHandlerTest {

    private ForgeAgentExceptionHandler handler;

    @BeforeEach
    void setUp() {
        this.handler = new ForgeAgentExceptionHandler();
    }

    @Test
    void validationExceptionReturnsBadRequestWithOriginalCodeAndMessage() {
        final var response = this.handler.handleValidation(
                new ValidationException("INVALID_AGENT_NAME", "Agent name is required."),
                this.request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_AGENT_NAME");
        assertThat(response.getBody().message()).isEqualTo("Agent name is required.");
    }

    @Test
    void notFoundExceptionReturnsNotFoundWithOriginalCodeAndMessage() {
        final var response = this.handler.handleNotFound(
                new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."),
                this.request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("WORKFLOW_NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("Workflow was not found.");
    }

    @Test
    void conflictExceptionReturnsConflictWithOriginalCodeAndMessage() {
        final var response = this.handler.handleConflict(
                new ConflictException("DUPLICATE_WORKFLOW_NAME", "A workflow with this name already exists in this project."),
                this.request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("DUPLICATE_WORKFLOW_NAME");
        assertThat(response.getBody().message()).isEqualTo("A workflow with this name already exists in this project.");
    }

    @Test
    void httpMessageNotReadableReturnsInvalidRequest() {
        final var response = this.handler.handleBadRequest(
                new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])),
                this.request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().message()).isEqualTo("Request body is invalid.");
    }

    @Test
    void methodArgumentNotValidReturnsInvalidRequest() throws NoSuchMethodException {
        final BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        final Method method = TestController.class.getDeclaredMethod("save", Object.class);
        final MethodParameter methodParameter = new MethodParameter(method, 0);

        final var response = this.handler.handleBadRequest(
                new MethodArgumentNotValidException(methodParameter, bindingResult),
                this.request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().message()).isEqualTo("Request body is invalid.");
    }

    @Test
    void dataIntegrityViolationReturnsGenericPersistenceConflict() {
        final var response = this.handler.handleDataIntegrity(
                new DataIntegrityViolationException("constraint"),
                this.request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("PERSISTENCE_CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("Request conflicts with existing Forge Agent configuration data.");
    }

    @Test
    void systemdUnavailableReturnsServiceUnavailableWithTypedCode() {
        final var response = this.handler.handleInfrastructureExecution(
                new InfrastructureExecutionException("SYSTEMD_UNAVAILABLE", "Systemd is not available on the selected host."),
                this.request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("SYSTEMD_UNAVAILABLE");
        assertThat(response.getBody().message()).isEqualTo("Systemd is not available on the selected host.");
    }

    @Test
    void otherInfrastructureFailuresRemainInternalError() {
        final var response = this.handler.handleInfrastructureExecution(
                new InfrastructureExecutionException("RUNTIME_COMMAND_FAILED", "Runtime command failed."),
                this.request()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("RUNTIME_COMMAND_FAILED");
    }

    @Test
    void unexpectedRuntimeExceptionReturnsInternalError() {
        final var response = this.handler.handleRuntime(new RuntimeException("boom"), this.request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("Forge Agent request failed.");
    }

    @Test
    void correlationIdIsPreserved() {
        final MockHttpServletRequest request = this.request();
        request.addHeader("X-Correlation-ID", "corr-123");

        final var response = this.handler.handleValidation(new ValidationException("INVALID_REQUEST", "Invalid."), request);

        assertThat(response.getBody().correlationId()).isEqualTo("corr-123");
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    private static final class TestController {
        private void save(@Valid final Object request) {
            // no-op
        }
    }
}
