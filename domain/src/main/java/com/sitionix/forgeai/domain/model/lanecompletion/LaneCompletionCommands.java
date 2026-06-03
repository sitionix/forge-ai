package com.sitionix.forgeai.domain.model.lanecompletion;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadTestUnitPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import java.util.List;
import java.util.UUID;

public final class LaneCompletionCommands {

    private LaneCompletionCommands() {
    }

    public record Analyzer(
            UUID ticketId,
            UUID laneId,
            String architectScope,
            String qaLeadScope,
            AgentTicket<ArchitectPayload> architectTicket,
            AgentTicket<QaLeadPayload> qaLeadTicket
    ) {
    }

    public record Architect(
            UUID ticketId,
            UUID laneId,
            String implementationScope,
            AgentTicket<ImplementBePayload> implementBeTicket,
            AgentTicket<ImplementFePayload> implementFeTicket,
            AgentTicket<ApiPayload> apiTicket,
            AgentTicket<EventPayload> eventTicket
    ) {
    }

    public record Api(
            UUID ticketId,
            UUID laneId,
            String summary,
            ApiLaneEvidencePayload evidence,
            List<ApiContractResult> contracts
    ) {
    }

    public record ApiContractResult(
            String scope,
            String method,
            String path,
            String operationId,
            List<String> notes,
            List<ApiGeneratedArtifact> artifacts
    ) {
    }

    public record ApiGeneratedArtifact(
            String dependency,
            String role,
            String kind,
            Long runId,
            List<String> notes
    ) {
    }

    public record ImplementBe(
            UUID ticketId,
            UUID laneId,
            String scope,
            AgentTicket<TestUnitPayload> testUnitTicket,
            AgentTicket<TestItPayload> testItTicket
    ) {
    }

    public record ImplementFe(
            UUID ticketId,
            UUID laneId,
            String scope,
            AgentTicket<TestUiPayload> testUiTicket
    ) {
    }

    public record QaLead(
            UUID ticketId,
            UUID laneId,
            String scope,
            boolean unitTestRequired,
            boolean integrationTestRequired,
            boolean uiTestRequired,
            AgentTicket<QaLeadTestUnitPayload> testUnitTicket,
            AgentTicket<QaLeadTestItPayload> testItTicket,
            AgentTicket<QaLeadTestUiPayload> testUiTicket
    ) {
    }

    public record ItTest(
            UUID ticketId,
            UUID laneId,
            String scope,
            AgentTicket<TestItCompletionPayload> completionReport
    ) {
    }

    public record UiTest(
            UUID ticketId,
            UUID laneId,
            String scope
    ) {
    }

    public record UnitTest(
            UUID ticketId,
            UUID laneId,
            String scope,
            AgentTicket<ReviewerPayload> reviewerTicket
    ) {
    }

    public record Reviewer(
            UUID ticketId
    ) {
    }
}
