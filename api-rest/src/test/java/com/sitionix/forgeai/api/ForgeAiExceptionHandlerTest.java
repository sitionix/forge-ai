package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.exception.ServicePropertyMissingException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
    void givenRequestValidationException_whenHandleRequestValidationException_thenReturnBadRequest() {
        //given
        final RequestValidationException exception = new RequestValidationException("coveredCases must not be empty");

        //when
        final ResponseEntity<Map<String, String>> actual = this.forgeAiExceptionHandler.handleRequestValidationException(exception);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(actual.getBody()).isEqualTo(Map.of(
                "error", "request_validation_failed",
                "message", "coveredCases must not be empty"
        ));
    }

    @Test
    void givenTicketNotFoundException_whenHandleTicketNotFoundException_thenReturnNotFound() {
        //given
        final TicketNotFoundException exception = new TicketNotFoundException("IT test ticket not found for laneId=123");

        //when
        final ResponseEntity<Map<String, String>> actual = this.forgeAiExceptionHandler.handleTicketNotFoundException(exception);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(actual.getBody()).isEqualTo(Map.of(
                "error", "ticket_not_found",
                "message", "IT test ticket not found for laneId=123"
        ));
    }

    @Test
    void givenLaneConflictException_whenHandleLaneConflictException_thenReturnConflict() {
        //given
        final LaneConflictException exception = new LaneConflictException("IT test lane type mismatch");

        //when
        final ResponseEntity<Map<String, String>> actual = this.forgeAiExceptionHandler.handleLaneConflictException(exception);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(actual.getBody()).isEqualTo(Map.of(
                "error", "lane_conflict",
                "message", "IT test lane type mismatch"
        ));
    }
}
