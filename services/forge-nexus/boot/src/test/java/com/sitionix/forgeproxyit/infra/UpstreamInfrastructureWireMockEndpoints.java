package com.sitionix.forgeproxyit.infra;

import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.wiremock.WiremockDefault;
import com.sitionix.forgeproxyit.infra.NexusInfrastructureMockMvcEndpoints.JarvisCommandRequest;
import com.sitionix.forgeproxyit.infra.NexusInfrastructureMockMvcEndpoints.KnowledgeQueryRequest;
import org.springframework.http.HttpStatus;

public final class UpstreamInfrastructureWireMockEndpoints {

    private UpstreamInfrastructureWireMockEndpoints() {
    }

    public static Endpoint<KnowledgeQueryRequest, Void> knowledgeQuery() {
        return Endpoint.createContract(
                "/api/v1/knowledge/query",
                HttpMethod.POST,
                KnowledgeQueryRequest.class,
                Void.class,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .matchesJson("knowledge-query-request.json")
                        .responseStatus(HttpStatus.OK.value())
        );
    }

    public static Endpoint<JarvisCommandRequest, Void> jarvisCommand() {
        return Endpoint.createContract(
                "/api/v1/jarvis/command",
                HttpMethod.POST,
                JarvisCommandRequest.class,
                Void.class,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .matchesJson("jarvis-command-request.json")
                        .responseStatus(HttpStatus.OK.value())
        );
    }
}
