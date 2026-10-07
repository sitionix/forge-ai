package com.sitionix.forgeproxyit;

import com.sitionix.forgeai.Application;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=Application.class,properties={"spring.docker.compose.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class NexusDialogueIT {
    private static final WireMockServer UPSTREAM = new WireMockServer(options().dynamicPort());
    static { UPSTREAM.start(); }
    private static final UUID RUN = UUID.randomUUID(), NODE = UUID.randomUUID();
    private static final String PATH = "/api/v1/workflow-runs/"+RUN+"/node-runs/"+NODE+"/dialogue";
    private static final String API = "/api/v1/infrastructure/agents/workflow-runs/"+RUN+"/node-runs/"+NODE+"/dialogue";
    private static final String STATE = "{\"nodeRunId\":\""+NODE+"\",\"state\":\"RUNNING\",\"revision\":4,\"summaryRevisionId\":null,\"latestRevision\":null,\"activeTurn\":null,\"completion\":null,\"messages\":{\"messages\":[],\"nextSequence\":2,\"hasMore\":false},\"turnCount\":2,\"maxTurns\":100,\"createdAt\":\"2026-10-07T00:00:00Z\",\"updatedAt\":\"2026-10-07T00:00:00Z\"}";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.ai.infrastructure.agent.base-url",UPSTREAM::baseUrl);
        registry.add("forge.ai.infrastructure.knowledge.base-url",UPSTREAM::baseUrl);
        registry.add("forge.ai.infrastructure.jarvis.base-url",UPSTREAM::baseUrl);
    }
    @BeforeEach void resetUpstream() { UPSTREAM.resetAll(); }
    @AfterAll static void stop() { UPSTREAM.stop(); }

    @Test void getAndCursorRoutesPreserveTypedState() throws Exception {
        UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo(PATH)).willReturn(okJson(STATE)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(API))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(4)).andExpect(jsonPath("$.state").value("RUNNING"));
        UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(PATH+"/messages"))
                .withQueryParam("afterSequence",equalTo("2")).withQueryParam("limit",equalTo("20"))
                .willReturn(okJson("{\"messages\":[],\"nextSequence\":2,\"hasMore\":false}")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(API+"/messages?afterSequence=2&limit=20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextSequence").value(2));
        UPSTREAM.verify(getRequestedFor(urlPathEqualTo(PATH+"/messages")).withQueryParam("limit",equalTo("20")));
    }

    @Test void mutationsPreserveIdsTextRevisionAndAcceptedOrDuplicateStatus() throws Exception {
        String body = json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",3)
                .put("text","😀 Text\nwith lines").toString();
        for (int upstreamStatus : new int[]{202,200}) {
            UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PATH+"/messages"))
                    .withRequestBody(equalToJson(body)).willReturn(aResponse().withStatus(upstreamStatus).withHeader("Content-Type","application/json").withBody(STATE)));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(API+"/messages")
                    .contentType("application/json").content(body)).andExpect(status().is(upstreamStatus));
        }
        UPSTREAM.verify(2,postRequestedFor(urlEqualTo(PATH+"/messages")).withRequestBody(equalToJson(body)));
    }

    @Test void summaryAndCompletionPreserveExactContractAndBusinessDraft() throws Exception {
        String summary = json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",4).toString();
        UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PATH+"/summary"))
                .withRequestBody(equalToJson(summary)).willReturn(aResponse().withStatus(202).withHeader("Content-Type","application/json").withBody(STATE)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(API+"/summary")
                .contentType("application/json").content(summary)).andExpect(status().isAccepted());
        String complete = json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",7)
                .put("summaryRevisionId",UUID.randomUUID().toString()).put("outputPortId",UUID.randomUUID().toString()).toString();
        var state = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(STATE);
        state.put("state","COMPLETED");
        var revision = state.putObject("latestRevision");
        revision.put("id",UUID.randomUUID().toString()).put("revision",7).put("turnId",UUID.randomUUID().toString())
                .put("kind","SUMMARY").put("inputRevision",5).put("createdAt","2026-10-07T00:00:00Z");
        var reply = revision.putObject("result");
        reply.put("message","Ready").put("readyForReview",true);
        reply.putArray("questions");reply.putArray("decisions");reply.putArray("sources");
        var draft = reply.putObject("draft");draft.put("unicode","😀\nTask");draft.putArray("items").add(42).add(true);
        UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PATH+"/complete"))
                .withRequestBody(equalToJson(complete)).willReturn(okJson(state.toString())));
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(API+"/complete")
                .contentType("application/json").content(complete)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).path("latestRevision").path("result").path("draft")).isEqualTo(draft);
        UPSTREAM.verify(postRequestedFor(urlEqualTo(PATH+"/complete")).withRequestBody(equalToJson(complete)));
    }

    @Test void conflictsAndValidationDoNotLoseTheirCodes() throws Exception {
        String body = json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",3).toString();
        UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PATH+"/summary"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type","application/json")
                        .withBody("{\"code\":\"DIALOGUE_REVISION_CONFLICT\",\"message\":\"Changed\",\"correlationId\":\"fixture\"}")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(API+"/summary")
                .contentType("application/json").content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIALOGUE_REVISION_CONFLICT"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(API+"/messages")
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        UPSTREAM.verify(0,postRequestedFor(urlEqualTo(PATH+"/messages")));
    }
}
