package com.sitionix.forgeai.it.infra;

import com.app_afesox.fgaisox.api_first.dto.CompleteAnalyzerLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteArchitectLaneRequest;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUiTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.sitionix.forgeai.api.ForgeAiLaneCompletionService;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class LaneCompletionTestFacade {

    private final ForgeAiLaneCompletionService service;
    private final CompletionRequestFixtureLoader fixtureLoader;

    public LaneCompletionTestFacade(final ForgeAiLaneCompletionService service,
                                    final CompletionRequestFixtureLoader fixtureLoader) {
        this.service = service;
        this.fixtureLoader = fixtureLoader;
    }

    public void completeAnalyzerLane(final UUID ticketId, final UUID laneId) {
        this.completeAnalyzerLane(ticketId, laneId, request -> {
        });
    }

    public void completeAnalyzerLane(final UUID ticketId,
                                     final UUID laneId,
                                     final Consumer<CompleteAnalyzerLaneRequestDTO> mutator) {
        final CompleteAnalyzerLaneRequestDTO request = this.fixtureLoader.read("requestCompleteAnalyzerLane.json", CompleteAnalyzerLaneRequestDTO.class, mutator);
        this.service.completeAnalyzerLane(ticketId, laneId, request);
    }

    public void completeArchitectLane(final UUID ticketId, final UUID laneId) {
        this.completeArchitectLane(ticketId, laneId, "requestCompleteArchitectLane.json", request -> {
        });
    }

    public void completeArchitectLane(final UUID ticketId,
                                      final UUID laneId,
                                      final String fixture,
                                      final Consumer<CompleteArchitectLaneRequest> mutator) {
        final CompleteArchitectLaneRequest request = this.fixtureLoader.read(fixture, CompleteArchitectLaneRequest.class, mutator);
        this.service.completeArchitectLane(ticketId, laneId, request);
    }

    public void completeApiLane(final UUID ticketId, final UUID laneId) {
        this.completeApiLane(ticketId, laneId, "requestCompleteApiLane.json", request -> {
        });
    }

    public void completeApiLane(final UUID ticketId,
                                final UUID laneId,
                                final Consumer<CompleteApiLaneRequest> mutator) {
        this.completeApiLane(ticketId, laneId, "requestCompleteApiLane.json", mutator);
    }

    public void completeApiLane(final UUID ticketId,
                                final UUID laneId,
                                final String fixture,
                                final Consumer<CompleteApiLaneRequest> mutator) {
        final CompleteApiLaneRequest request = this.fixtureLoader.read(fixture, CompleteApiLaneRequest.class, mutator);
        this.service.completeApiLane(ticketId, laneId, request);
    }

    public void completeImplementBeLane(final UUID ticketId, final UUID laneId) {
        this.completeImplementBeLane(ticketId, laneId, request -> {
        });
    }

    public void completeImplementBeLane(final UUID ticketId,
                                        final UUID laneId,
                                        final Consumer<CompleteImplementBeLaneRequestDTO> mutator) {
        final CompleteImplementBeLaneRequestDTO request = this.fixtureLoader.read("requestCompleteImplementBeLane.json", CompleteImplementBeLaneRequestDTO.class, mutator);
        this.service.completeImplementBeLane(ticketId, laneId, request);
    }

    public void completeImplementFeLane(final UUID ticketId, final UUID laneId) {
        this.completeImplementFeLane(ticketId, laneId, request -> {
        });
    }

    public void completeImplementFeLane(final UUID ticketId,
                                        final UUID laneId,
                                        final Consumer<CompleteImplementFeLaneRequestDTO> mutator) {
        final CompleteImplementFeLaneRequestDTO request = this.fixtureLoader.read("requestCompleteImplementFeLane.json", CompleteImplementFeLaneRequestDTO.class, mutator);
        this.service.completeImplementFeLane(ticketId, laneId, request);
    }

    public void completeQaLeadLaneBackend(final UUID ticketId, final UUID laneId) {
        this.completeQaLeadLaneBackend(ticketId, laneId, request -> {
        });
    }

    public void completeQaLeadLaneBackend(final UUID ticketId,
                                          final UUID laneId,
                                          final Consumer<CompleteQaLeadLaneRequestDTO> mutator) {
        final CompleteQaLeadLaneRequestDTO request = this.fixtureLoader.read("requestCompleteQaLeadLaneBackend.json", CompleteQaLeadLaneRequestDTO.class, mutator);
        this.service.completeQaLeadLane(ticketId, laneId, request);
    }

    public void completeQaLeadLaneBackendNotRequired(final UUID ticketId, final UUID laneId) {
        final CompleteQaLeadLaneRequestDTO request = this.fixtureLoader.read("requestCompleteQaLeadLaneBackendNotRequired.json", CompleteQaLeadLaneRequestDTO.class);
        this.service.completeQaLeadLane(ticketId, laneId, request);
    }

    public void completeItTestLane(final UUID ticketId, final UUID laneId) {
        this.completeItTestLane(ticketId, laneId, request -> {
        });
    }

    public void completeItTestLane(final UUID ticketId,
                                   final UUID laneId,
                                   final Consumer<CompleteItTestLaneRequestDTO> mutator) {
        final CompleteItTestLaneRequestDTO request = this.fixtureLoader.read("requestCompleteItTestLane.json", CompleteItTestLaneRequestDTO.class, mutator);
        this.service.completeItTestLane(ticketId, laneId, request);
    }

    public void completeUiTestLane(final UUID ticketId, final UUID laneId) {
        this.completeUiTestLane(ticketId, laneId, request -> {
        });
    }

    public void completeUiTestLane(final UUID ticketId,
                                   final UUID laneId,
                                   final Consumer<CompleteUiTestLaneRequestDTO> mutator) {
        final CompleteUiTestLaneRequestDTO request = this.fixtureLoader.read("requestCompleteUiTestLane.json", CompleteUiTestLaneRequestDTO.class, mutator);
        this.service.completeUiTestLane(ticketId, laneId, request);
    }

    public void completeUnitTestLane(final UUID ticketId, final UUID laneId) {
        this.completeUnitTestLane(ticketId, laneId, request -> {
        });
    }

    public void completeUnitTestLane(final UUID ticketId,
                                     final UUID laneId,
                                     final Consumer<CompleteUnitTestLaneRequestDTO> mutator) {
        final CompleteUnitTestLaneRequestDTO request = this.fixtureLoader.read("requestCompleteUnitTestLane.json", CompleteUnitTestLaneRequestDTO.class, mutator);
        this.service.completeUnitTestLane(ticketId, laneId, request);
    }

    public void completeReviewerLane(final UUID ticketId) {
        this.service.completeReviewerLane(ticketId);
    }
}
