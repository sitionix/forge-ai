package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.api.ForgeAiOperatorTicketApi;
import com.app_afesox.fgaisox.api_first.dto.TicketOperatorSnapshotResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.TicketOperatorRunDTO;
import com.app_afesox.fgaisox.api_first.dto.TicketOperatorRunsResponseDTO;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import com.sitionix.forgeai.mapper.ForgeAiOperatorApiMapper;

@RestController
@RequiredArgsConstructor
public class ForgeAiTicketOperatorController implements ForgeAiOperatorTicketApi {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final ManageTicketOperatorRuns manageTicketOperatorRuns;
    private final LaneExecutionRepository laneExecutionRepository;
    private final TicketRepository ticketRepository;
    private final TicketOperatorStreamResourceFactory ticketOperatorStreamResourceFactory;
    private final ForgeAiOperatorApiMapper forgeAiOperatorApiMapper;

    @Override
    public ResponseEntity<TicketOperatorRunsResponseDTO> getActiveOperatorTickets() {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asTicketOperatorRunsResponse(
                this.manageTicketOperatorRuns.findActiveTicketRuns().stream()
                        .map(this.forgeAiOperatorApiMapper::asTicketOperatorRun)
                        .toList()
        ));
    }

    @Override
    public ResponseEntity<TicketOperatorSnapshotResponseDTO> getOperatorTicket(final UUID ticketId,
                                                                               final String verbosity) {
        final TicketOperatorRun run = this.manageTicketOperatorRuns.getTicketRun(ticketId);
        final List<LaneExecution> activeExecutions = this.laneExecutionRepository.findActiveExecutionsByTicketId(ticketId);
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalStateException("Ticket not found: " + ticketId));
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asTicketOperatorSnapshot(
                this.forgeAiOperatorApiMapper.asTicketOperatorRunForSnapshot(run),
                this.forgeAiOperatorApiMapper.asTicketOperatorLaneSummary(ticket),
                activeExecutions.stream()
                        .map(this.forgeAiOperatorApiMapper::asTicketOperatorExecution)
                        .toList(),
                this.manageTicketOperatorRuns.recentEvents(ticketId, this.verbosityOrDefault(verbosity)).stream()
                        .map(this.forgeAiOperatorApiMapper::asTicketOperatorEvent)
                        .toList()
        ));
    }

    @Override
    public ResponseEntity<Resource> streamOperatorTicket(final UUID ticketId,
                                                         final String watcherId,
                                                         final String verbosity,
                                                         final Boolean stopOnWindowClose,
                                                         final Boolean replay) {
        final Resource resource = this.ticketOperatorStreamResourceFactory.create(
                this.manageTicketOperatorRuns.stream(
                        ticketId,
                        watcherId,
                        this.verbosityOrDefault(verbosity),
                        stopOnWindowClose == null || stopOnWindowClose
                ),
                replay == null || replay
        );
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .body(resource);
    }

    @Override
    public ResponseEntity<TicketOperatorRunDTO> heartbeatOperatorTicketWatcher(final UUID ticketId,
                                                                               final String watcherId) {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asTicketOperatorRun(
                this.manageTicketOperatorRuns.heartbeat(ticketId, watcherId)
        ));
    }

    @Override
    public ResponseEntity<TicketOperatorRunDTO> interruptOperatorTicket(final UUID ticketId,
                                                                        final String reason) {
        return ResponseEntity.ok(this.forgeAiOperatorApiMapper.asTicketOperatorRun(
                this.manageTicketOperatorRuns.interruptTicket(
                        ticketId,
                        reason == null || reason.isBlank() ? "OPERATOR_TICKET_INTERRUPT" : reason
                )
        ));
    }

    private String verbosityOrDefault(final String verbosity) {
        return verbosity == null || verbosity.isBlank() ? "minimal" : verbosity;
    }
}
