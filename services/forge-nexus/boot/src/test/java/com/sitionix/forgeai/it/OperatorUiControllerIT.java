package com.sitionix.forgeai.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.TicketJpaRepository;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class OperatorUiControllerIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TicketJpaRepository ticketJpaRepository;

    @Test
    @DisplayName("Should expose full operator UI task workflow through generated API endpoints")
    void givenOperatorUiEndpoints_whenCreateInspectAndExecuteTicket_thenReturnGeneratedResponses() throws Exception {
        this.mockMvc.perform(get("/api/v1/forge-ai/operator/ui/services")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[?(@.id == 'atmssox' && @.group == 'BACKEND')]").isNotEmpty())
                .andExpect(jsonPath("$.services[?(@.id == 'sitionix-spa' && @.group == 'FRONTEND')]").isNotEmpty())
                .andExpect(jsonPath("$.services[?(@.id == 'forge-ai' && @.group == 'BACKEND')]").isNotEmpty())
                .andExpect(jsonPath("$.services[?(@.id == 'app-afesox' && @.group == 'TOOL')]").isNotEmpty())
                .andExpect(jsonPath("$.services[?(@.id == 'forge-it' && @.group == 'TOOL')]").isNotEmpty());

        final MvcResult createResult = this.mockMvc.perform(post("/api/v1/forge-ai/operator/ui/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticket": "SITIONIX-34",
                                  "task": "operator UI generated endpoint IT",
                                  "serviceIds": ["atmssox"],
                                  "sourceTerminalTty": "/dev/ttys999"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").isNotEmpty())
                .andExpect(jsonPath("$.ticketKey").value("SITIONIX-34"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andReturn();

        final UUID ticketId = this.ticketId(createResult);
        final TicketDocument ticket = this.ticketJpaRepository.findById(ticketId).orElseThrow();
        final LaneDocument analyzerLane = ticket.getLanes().stream()
                .filter(lane -> lane.getType() == Agent.ANALYZER)
                .findFirst()
                .orElseThrow();

        this.mockMvc.perform(get("/api/v1/forge-ai/operator/ui/tickets")
                        .queryParam("limit", "25")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets[?(@.ticketId == '%s' && @.status == 'OPEN')]".formatted(ticketId)).isNotEmpty())
                .andExpect(jsonPath("$.tickets[?(@.ticketId == '%s')].laneCounts.notStarted".formatted(ticketId)).isNotEmpty());

        this.mockMvc.perform(get("/api/v1/forge-ai/operator/ui/tickets/{ticketId}/graph", ticketId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.ticketKey").value("SITIONIX-34"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.taskDescription").value("operator UI generated endpoint IT"))
                .andExpect(jsonPath("$.lanes[?(@.agent == 'ANALYZER' && @.scope == 'automationservice-sox')]").isNotEmpty())
                .andExpect(jsonPath("$.lanes[?(@.agent == 'ARCHITECT' && @.scope == 'automationservice-sox')]").isNotEmpty());

        this.mockMvc.perform(get("/api/v1/forge-ai/operator/ui/tickets/{ticketId}/lanes/{laneId}", ticketId, analyzerLane.getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.laneId").value(analyzerLane.getId().toString()))
                .andExpect(jsonPath("$.agent").value("ANALYZER"))
                .andExpect(jsonPath("$.scope").value("automationservice-sox"))
                .andExpect(jsonPath("$.status").value("READY_TO_START"))
                .andExpect(jsonPath("$.taskDescription").value("operator UI generated endpoint IT"))
                .andExpect(jsonPath("$.steps[0].stepId").value("scope_slicing"))
                .andExpect(jsonPath("$.events").isArray());

        this.mockMvc.perform(post("/api/v1/forge-ai/operator/ui/tickets/{ticketId}/execute", ticketId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.status").value("READY_TO_START"));

        assertThat(this.ticketJpaRepository.findById(ticketId).orElseThrow().getStatus())
                .isEqualTo(TicketStatus.READY_TO_START);

        this.mockMvc.perform(delete("/api/v1/forge-ai/operator/ui/tickets/{ticketId}", ticketId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        assertThat(this.ticketJpaRepository.findById(ticketId)).isEmpty();

        this.mockMvc.perform(delete("/api/v1/forge-ai/operator/ui/tickets/{ticketId}", ticketId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.title").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("Should reject invalid operator UI create request through generated validation")
    void givenInvalidCreateRequest_whenCreateTicket_thenReturnBadRequest() throws Exception {
        this.mockMvc.perform(post("/api/v1/forge-ai/operator/ui/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticket": "",
                                  "task": "",
                                  "serviceIds": []
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should expose local services sanity view from services yaml")
    void givenLocalServicesRequest_whenInspectServices_thenReturnWorkspaceRuntimeAndContractData() throws Exception {
        this.mockMvc.perform(get("/api/v1/forge-ai/operator/ui/local-services")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[?(@.serviceId == 'forge-ai' && @.path == 'forge-ai')]").isNotEmpty())
                .andExpect(jsonPath("$.services[?(@.serviceId == 'app-afesox' && @.path == 'app-afesox')]").isNotEmpty())
                .andExpect(jsonPath("$.services[?(@.serviceId == 'app-afesox' && @.repository == 'Sitionix/app-afesox')]").isNotEmpty())
                .andExpect(jsonPath("$.services[?(@.serviceId == 'forge-ai')].exists").isNotEmpty())
                .andExpect(jsonPath("$.services[?(@.serviceId == 'forge-ai')].serviceRuntimeStatus").isNotEmpty());

        this.mockMvc.perform(get("/api/v1/forge-ai/operator/ui/local-services/{serviceId}", "forge-ai")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service.serviceId").value("forge-ai"))
                .andExpect(jsonPath("$.service.repository").value("Sitionix/forge-ai"))
                .andExpect(jsonPath("$.database.required").value(true))
                .andExpect(jsonPath("$.database.type").value("mongodb"))
                .andExpect(jsonPath("$.contractReferences[?(@.refKey == 'api' && @.sourceRepo == 'app-afesox')]").isNotEmpty());
    }

    private UUID ticketId(final MvcResult result) throws Exception {
        final JsonNode body = this.objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.path("ticketId").asText());
    }
}
