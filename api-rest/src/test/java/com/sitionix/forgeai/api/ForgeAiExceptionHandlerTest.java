package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.exception.ServicePropertyMissingException;
import jakarta.validation.Valid;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class ForgeAiExceptionHandlerTest {

    private ForgeAiExceptionHandler forgeAiExceptionHandler;

    @BeforeEach
    void setUp() {
        this.forgeAiExceptionHandler = new ForgeAiExceptionHandler();
    }

    @Test
    void givenServicePropertyMissingException_whenHandleServicePropertyMissing_thenReturnBadRequest() {
        //given
        final ServicePropertyMissingException exception = new ServicePropertyMissingException("missing deploy.repo");

        //when
        final ResponseEntity<Map<String, String>> actual = this.forgeAiExceptionHandler.handleServicePropertyMissing(exception);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(actual.getBody()).isEqualTo(Map.of(
                "error", "service_config_property_missing",
                "message", "missing deploy.repo"
        ));
    }

    @Test
    void givenServicePropsRelatedNullPointerException_whenHandleNullPointerException_thenReturnBadRequest() {
        //given
        final NullPointerException exception = new NullPointerException("npe");
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.sitionix.forgeai.config.ServiceProps", "getServices", "ServiceProps.java", 12)
        });

        //when
        final ResponseEntity<Map<String, String>> actual = this.forgeAiExceptionHandler.handleNullPointerException(exception);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(actual.getBody()).isEqualTo(Map.of(
                "error", "service_config_property_missing",
                "message", "Service configuration is incomplete"
        ));
    }

    @Test
    void givenGenericNullPointerException_whenHandleNullPointerException_thenReturnInternalServerError() {
        //given
        final NullPointerException exception = new NullPointerException("npe");
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.sitionix.forgeai.api.ForgeAiController", "startForge", "ForgeAiController.java", 30)
        });

        //when
        final ResponseEntity<Map<String, String>> actual = this.forgeAiExceptionHandler.handleNullPointerException(exception);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(actual.getBody()).isEqualTo(Map.of(
                "error", "internal_error",
                "message", "Unexpected internal error"
        ));
    }

    @Test
    void givenScopeMismatchException_whenHandleScopeMismatchException_thenReturnBadRequest() {
        //given
        final ScopeMismatchException exception = new ScopeMismatchException("scope mismatch");

        //when
        final ResponseEntity<Map<String, String>> actual = this.forgeAiExceptionHandler.handleScopeMismatchException(exception);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(actual.getBody()).isEqualTo(Map.of(
                "error", "scope_mismatch",
                "message", "scope mismatch"
        ));
    }

    @Test
    void givenMethodArgumentNotValidException_whenHandleMethodArgumentNotValidException_thenReturnBadRequestWithValidationDetails() throws NoSuchMethodException {
        //given
        final Object requestBody = new Object();
        final BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(requestBody, "completeQaLeadLaneRequestDTO");
        bindingResult.addError(new FieldError("completeQaLeadLaneRequestDTO", "integrationTestCases[0].flow.path", "api/v1/agents/invalid", false, null, null, "must match \"^/\""));
        final Method method = TestController.class.getDeclaredMethod("completeQaLeadLane", Object.class);
        final MethodParameter methodParameter = new MethodParameter(method, 0);
        final MethodArgumentNotValidException exception = new MethodArgumentNotValidException(methodParameter, bindingResult);

        //when
        final ResponseEntity<Map<String, Object>> actual = this.forgeAiExceptionHandler.handleMethodArgumentNotValidException(exception);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(actual.getBody()).isEqualTo(Map.of(
                "code", HttpStatus.BAD_REQUEST.value(),
                "title", "VALIDATION_FAILED",
                "details", "integrationTestCases[0].flow.path must match \"^/\" (rejected: api/v1/agents/invalid)"
        ));
    }

    private static final class TestController {
        private void completeQaLeadLane(@Valid final Object body) {
            // no-op
        }
    }
}
