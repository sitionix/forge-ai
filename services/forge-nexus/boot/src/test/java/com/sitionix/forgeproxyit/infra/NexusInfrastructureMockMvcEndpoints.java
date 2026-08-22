package com.sitionix.forgeproxyit.infra;

import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;
import org.springframework.http.HttpStatus;

public final class NexusInfrastructureMockMvcEndpoints {

    private NexusInfrastructureMockMvcEndpoints() {
    }

    public static Endpoint<KnowledgeQueryRequest, Void> knowledgeQuery() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/knowledge/query?trace=knowledge-it",
                HttpMethod.POST,
                KnowledgeQueryRequest.class,
                Void.class,
                (MockmvcDefault) context -> context
                        .withRequest("knowledge-query-request.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<JarvisCommandRequest, Void> jarvisCommand() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/jarvis/command",
                HttpMethod.POST,
                JarvisCommandRequest.class,
                Void.class,
                (MockmvcDefault) context -> context
                        .withRequest("jarvis-command-request.json")
                        .expectStatus(HttpStatus.OK.value())
        );
    }

    public record KnowledgeQueryRequest(String query, String scope) {
    }

    public record JarvisCommandRequest(String command, String target) {
    }
}
