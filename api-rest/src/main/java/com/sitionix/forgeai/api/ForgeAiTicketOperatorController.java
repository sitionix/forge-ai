package com.sitionix.forgeai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import com.sitionix.forgeai.domain.usecase.TicketOperatorEventStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/forge-ai/operator/tickets")
public class ForgeAiTicketOperatorController {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final ManageTicketOperatorRuns manageTicketOperatorRuns;
    private final LaneExecutionRepository laneExecutionRepository;
    private final TicketRepository ticketRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/active")
    public ResponseEntity<List<TicketOperatorRunResponse>> activeTickets() {
        return ResponseEntity.ok(this.manageTicketOperatorRuns.findActiveTicketRuns().stream()
                .map(this::asRunResponse)
                .toList());
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketOperatorTicketResponse> ticket(@PathVariable final UUID ticketId,
                                                               @RequestParam(defaultValue = "minimal") final String verbosity) {
        final TicketOperatorRun run = this.manageTicketOperatorRuns.getTicketRun(ticketId);
        final List<LaneExecution> activeExecutions = this.laneExecutionRepository.findActiveExecutionsByTicketId(ticketId);
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalStateException("Ticket not found: " + ticketId));
        return ResponseEntity.ok(new TicketOperatorTicketResponse(
                this.asRunResponse(run),
                this.laneSummary(ticket),
                activeExecutions.stream().map(this::asExecutionResponse).toList(),
                this.manageTicketOperatorRuns.recentEvents(ticketId, verbosity)
        ));
    }

    @GetMapping(value = "/{ticketId}/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable final UUID ticketId,
                                                        @RequestParam final String watcherId,
                                                        @RequestParam(defaultValue = "minimal") final String verbosity,
                                                        @RequestParam(defaultValue = "true") final boolean stopOnWindowClose,
                                                        @RequestParam(defaultValue = "true") final boolean replay) {
        final TicketOperatorEventStream stream = this.manageTicketOperatorRuns.stream(ticketId, watcherId, verbosity, stopOnWindowClose);
        final StreamingResponseBody body = outputStream -> {
            try (stream; BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
                if (replay) {
                    for (final TicketOperatorEvent event : stream.replay()) {
                        writer.write(this.objectMapper.writeValueAsString(event));
                        writer.newLine();
                    }
                    writer.flush();
                }
                while (true) {
                    final TicketOperatorEvent event = stream.take();
                    writer.write(this.objectMapper.writeValueAsString(event));
                    writer.newLine();
                    writer.flush();
                    if (this.isTerminalTicketEvent(event)) {
                        break;
                    }
                }
            } catch (final InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        };
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .body(body);
    }

    @PostMapping("/{ticketId}/watchers/{watcherId}/heartbeat")
    public ResponseEntity<TicketOperatorRunResponse> heartbeat(@PathVariable final UUID ticketId,
                                                               @PathVariable final String watcherId) {
        return ResponseEntity.ok(this.asRunResponse(this.manageTicketOperatorRuns.heartbeat(ticketId, watcherId)));
    }

    @PostMapping("/{ticketId}/interrupt")
    public ResponseEntity<TicketOperatorRunResponse> interrupt(@PathVariable final UUID ticketId,
                                                               @RequestParam(defaultValue = "OPERATOR_TICKET_INTERRUPT") final String reason) {
        return ResponseEntity.ok(this.asRunResponse(this.manageTicketOperatorRuns.interruptTicket(ticketId, reason)));
    }

    private boolean isTerminalTicketEvent(final TicketOperatorEvent event) {
        return List.of("TICKET_COMPLETED", "TICKET_CANCELLED", "TICKET_FAILED").contains(event.getEventType());
    }

    private TicketOperatorRunResponse asRunResponse(final TicketOperatorRun run) {
        return new TicketOperatorRunResponse(
                run.getTicketId(),
                run.getTicketKey(),
                run.getStatus().name(),
                run.getWatcherId(),
                run.getLastHeartbeatAt(),
                run.getCancelRequestedAt(),
                run.getCancelledAt(),
                run.getInterruptReason(),
                run.getActiveExecutionIds(),
                run.getActiveLaneIds(),
                run.getLastProgressEvent(),
                run.getLastProgressAt()
        );
    }

    private ExecutionResponse asExecutionResponse(final LaneExecution execution) {
        return new ExecutionResponse(
                execution.getId(),
                execution.getLaneId(),
                execution.getAgentId(),
                execution.getScope(),
                execution.getStatus() == null ? null : execution.getStatus().name(),
                execution.getProcessPid(),
                execution.getSessionId(),
                execution.getThreadId(),
                execution.getActiveTurnId(),
                execution.getCurrentStepId(),
                execution.getCurrentStepOrder(),
                execution.getCurrentStepTitle()
        );
    }

    private Map<String, Long> laneSummary(final Ticket ticket) {
        return Map.of(
                "completed", ticket.getLanes().stream().filter(lane -> lane.getStatus() == LaneStatus.COMPLETED).count(),
                "inProgress", ticket.getLanes().stream().filter(lane -> lane.getStatus() == LaneStatus.IN_PROGRESS).count(),
                "ready", ticket.getLanes().stream().filter(lane -> lane.getStatus() == LaneStatus.READY_TO_START).count(),
                "notStarted", ticket.getLanes().stream().filter(lane -> lane.getStatus() == LaneStatus.NOT_STARTED).count(),
                "notNeeded", ticket.getLanes().stream().filter(lane -> lane.getStatus() == LaneStatus.NOT_NEEDED).count()
        );
    }

    private record TicketOperatorRunResponse(
            UUID ticketId,
            String ticketKey,
            String status,
            String watcherId,
            LocalDateTime lastHeartbeatAt,
            LocalDateTime cancelRequestedAt,
            LocalDateTime cancelledAt,
            String interruptReason,
            List<UUID> activeExecutionIds,
            List<UUID> activeLaneIds,
            String lastProgressEvent,
            LocalDateTime lastProgressAt
    ) {
    }

    private record ExecutionResponse(
            UUID executionId,
            UUID laneId,
            String agentId,
            String scope,
            String status,
            Long processPid,
            String codexSessionId,
            String codexThreadId,
            String activeTurnId,
            String activeStepId,
            Integer activeStepOrder,
            String activeStepTitle
    ) {
    }

    private record TicketOperatorTicketResponse(
            TicketOperatorRunResponse run,
            Map<String, Long> laneSummary,
            List<ExecutionResponse> activeExecutions,
            List<TicketOperatorEvent> recentEvents
    ) {
    }
}
