package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileUpdateRequest;
import com.sitionix.forgeai.api.activeprofile.ActiveProfileResponse;
import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;
import com.sitionix.forgeit.domain.endpoint.wiremock.WiremockDefault;
import org.springframework.http.HttpStatus;

public final class KnowledgeActiveProfileEndpoint {

    public static final Endpoint<Object, ActiveProfileResponse> NEXUS_GET_ACTIVE_PROFILE =
            nexusGet("/api/v1/infrastructure/knowledge/active-profile",
                    ActiveProfileResponse.class,
                    HttpStatus.OK,
                    "responseDefaultGetActiveProfile.json");

    public static final Endpoint<ActiveLlmProfileUpdateRequest, ActiveLlmProfileResponse> NEXUS_PUT_ACTIVE_LLM_PROFILE =
            nexusPut("/api/v1/infrastructure/knowledge/active-profile/llm-profile",
                    ActiveLlmProfileUpdateRequest.class,
                    ActiveLlmProfileResponse.class,
                    "requestDefaultUpdateActiveLlmProfile.json",
                    HttpStatus.OK,
                    "responseDefaultUpdateActiveLlmProfile.json");

    public static final Endpoint<ActiveLlmProfileUpdateRequest, InfrastructureErrorResponse> NEXUS_PUT_ACTIVE_LLM_PROFILE_VALIDATION_FAILED =
            nexusPut("/api/v1/infrastructure/knowledge/active-profile/llm-profile",
                    ActiveLlmProfileUpdateRequest.class,
                    InfrastructureErrorResponse.class,
                    "requestDefaultInvalidUpdateActiveLlmProfile.json",
                    HttpStatus.BAD_REQUEST,
                    "responseDefaultActiveProfileValidationFailed.json");

    public static final Endpoint<ActiveLlmProfileUpdateRequest, InfrastructureErrorResponse> NEXUS_PUT_ACTIVE_LLM_PROFILE_UNKNOWN_FIELD =
            nexusPut("/api/v1/infrastructure/knowledge/active-profile/llm-profile",
                    ActiveLlmProfileUpdateRequest.class,
                    InfrastructureErrorResponse.class,
                    "requestDefaultUnknownFieldUpdateActiveLlmProfile.json",
                    HttpStatus.BAD_REQUEST,
                    "responseDefaultActiveProfileUnreadableBody.json");

    public static final Endpoint<ActiveLlmProfileUpdateRequest, InfrastructureErrorResponse> NEXUS_PUT_ACTIVE_LLM_PROFILE_REVISION_CONFLICT =
            nexusPut("/api/v1/infrastructure/knowledge/active-profile/llm-profile",
                    ActiveLlmProfileUpdateRequest.class,
                    InfrastructureErrorResponse.class,
                    "requestDefaultUpdateActiveLlmProfile.json",
                    HttpStatus.CONFLICT,
                    "responseDefaultRevisionConflict.json");

    public static final Endpoint<Object, InfrastructureErrorResponse> NEXUS_GET_ACTIVE_PROFILE_UPSTREAM_UNAVAILABLE =
            nexusGet("/api/v1/infrastructure/knowledge/active-profile",
                    InfrastructureErrorResponse.class,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "responseDefaultActiveProfileUpstreamUnavailable.json");

    public static final Endpoint<Object, InfrastructureErrorResponse> NEXUS_GET_ACTIVE_PROFILE_MALFORMED_UPSTREAM_ERROR =
            nexusGet("/api/v1/infrastructure/knowledge/active-profile",
                    InfrastructureErrorResponse.class,
                    HttpStatus.BAD_GATEWAY,
                    "responseDefaultActiveProfileUpstreamInvalidResponse.json");

    public static final Endpoint<Object, KnowledgeActiveProfileResponse> UPSTREAM_GET_ACTIVE_PROFILE =
            upstreamGet("/api/v1/knowledge/active-profile",
                    KnowledgeActiveProfileResponse.class,
                    HttpStatus.OK,
                    "responseDefaultMappingGetActiveProfile.json");

    public static final Endpoint<KnowledgeActiveLlmProfileRequest, KnowledgeActiveLlmProfileResponse> UPSTREAM_PUT_ACTIVE_LLM_PROFILE =
            upstreamPut("/api/v1/knowledge/active-profile/llm-profile",
                    KnowledgeActiveLlmProfileRequest.class,
                    KnowledgeActiveLlmProfileResponse.class,
                    "requestDefaultUpdateActiveLlmProfile.json",
                    HttpStatus.OK,
                    "responseDefaultMappingUpdateActiveLlmProfile.json");

    public static final Endpoint<KnowledgeActiveLlmProfileRequest, InfrastructureErrorResponse> UPSTREAM_PUT_ACTIVE_LLM_PROFILE_REVISION_CONFLICT =
            upstreamPut("/api/v1/knowledge/active-profile/llm-profile",
                    KnowledgeActiveLlmProfileRequest.class,
                    InfrastructureErrorResponse.class,
                    "requestDefaultUpdateActiveLlmProfile.json",
                    HttpStatus.CONFLICT,
                    "responseDefaultMappingRevisionConflict.json");

    public static final Endpoint<Object, InfrastructureErrorResponse> UPSTREAM_GET_ACTIVE_PROFILE_MALFORMED_ERROR =
            upstreamGet("/api/v1/knowledge/active-profile",
                    InfrastructureErrorResponse.class,
                    HttpStatus.CONFLICT,
                    "responseDefaultMappingMalformedError.json");

    private static <Res> Endpoint<Object, Res> nexusGet(final String path,
                                                        final Class<Res> responseClass,
                                                        final HttpStatus status,
                                                        final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.GET, Object.class, responseClass,
                (MockmvcDefault) context -> context
                        .expectStatus(status.value())
                        .expectResponse(responseFixture));
    }

    private static <Req, Res> Endpoint<Req, Res> nexusPut(final String path,
                                                          final Class<Req> requestClass,
                                                          final Class<Res> responseClass,
                                                          final String requestFixture,
                                                          final HttpStatus status,
                                                          final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.PUT, requestClass, responseClass,
                (MockmvcDefault) context -> context
                        .withRequest(requestFixture)
                        .expectStatus(status.value())
                        .expectResponse(responseFixture));
    }

    private static <Res> Endpoint<Object, Res> upstreamGet(final String path,
                                                           final Class<Res> responseClass,
                                                           final HttpStatus status,
                                                           final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.GET, Object.class, responseClass,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .responseStatus(status.value())
                        .responseBody(responseFixture));
    }

    private static <Req, Res> Endpoint<Req, Res> upstreamPut(final String path,
                                                             final Class<Req> requestClass,
                                                             final Class<Res> responseClass,
                                                             final String requestFixture,
                                                             final HttpStatus status,
                                                             final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.PUT, requestClass, responseClass,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .matchesJson(requestFixture)
                        .responseStatus(status.value())
                        .responseBody(responseFixture));
    }

    private KnowledgeActiveProfileEndpoint() {
    }
}
