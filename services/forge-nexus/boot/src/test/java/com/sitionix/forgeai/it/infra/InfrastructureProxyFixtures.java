package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeit.wiremock.api.Parameter;
import org.springframework.http.HttpStatus;

public final class InfrastructureProxyFixtures {

    public static void reset(final ProxyTestManager testManager) {
        testManager.wiremock().reset();
    }

    public static void stubCommonKnowledgeRoutes(final ProxyTestManager testManager) {
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeSources()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeOverview()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeInventoryBuild()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeInventoryStatus()).createDefault();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeInventoryFiles())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamInventoryJavaExtension())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyInventoryFiles.json"))
                .create();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisBuild()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisRetryFailed()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisJob()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisJobStop()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisStatus()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisFiles()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisDiagnostics()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifest()).createDefault();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNodes())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodes.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdges())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdges.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNode())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodeDetail.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdge())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphRevisionA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdgeDetail.json"))
                .create();
    }

    public static void stubCommonJarvisRoutes(final ProxyTestManager testManager) {
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisStatus()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisActions()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisCommand()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamJarvisChat()).createDefault();
    }

    public static void stubPostBodyRoutes(final ProxyTestManager testManager) {
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeInventoryBuildBody())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamTraceOne())
                .applyDefault(context -> context
                        .matchesJson("requestProxyInventoryBuild.json")
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyInventoryBuild.json"))
                .create();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisBuildBody()).createDefault();
        testManager.wiremock().createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeAnalysisJobStopBody()).createDefault();
    }

    public static void stubCorrelationRoute(final ProxyTestManager testManager) {
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisStatus())
                .plainUrl()
                .header("X-Correlation-Id", "corr-123")
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyJarvisStatus.json"))
                .create();
    }

    public static void stubGeneratedCorrelationRoute(final ProxyTestManager testManager) {
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisActions())
                .plainUrl()
                .header("X-Correlation-Id", Parameter.matches("[A-Za-z0-9._:-]{1,128}"))
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyJarvisActions.json"))
                .create();
    }

    public static void stubSafeKnowledgeCorrelationRoute(final ProxyTestManager testManager) {
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus())
                .plainUrl()
                .header("X-Correlation-Id", Parameter.matches("[A-Za-z0-9._:-]{1,128}"))
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeStatus.json"))
                .create();
    }

    public static void stubProxyErrorRoutes(final ProxyTestManager testManager) {
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamSlowCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeStatus.json"))
                .delayForResponse(500)
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamTimeoutCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyKnowledgeStatus.json"))
                .delayForResponse(3000)
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamTooLargeKnowledgeStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamResponseTooLargeCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyTooLarge.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamTooLargeJarvisStatus())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamResponseTooLargeCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyTooLarge.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatusNonJson())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamNonJsonCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyInvalidJson.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeStatusServerError())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamServerErrorCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .responseBody("responseProxyUpstreamServerError.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisChatServerError())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamServerErrorCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .responseBody("responseProxyUpstreamServerError.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamJarvisCommandServerError())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamServerErrorCase())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .responseBody("responseProxyUpstreamServerError.json"))
                .create();
    }

    public static void stubGraphParityRoutes(final ProxyTestManager testManager) {
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifestFiltered())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphFilteredManifest())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphManifestFiltered.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphSnapshotExpired())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphExpiredRevision())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.GONE.value())
                        .responseBody("responseProxyGraphSnapshotExpired.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphMissingNode())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.NOT_FOUND.value())
                        .responseBody("responseProxyGraphNodeNotFound.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphMissingEdge())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.NOT_FOUND.value())
                        .responseBody("responseProxyGraphEdgeNotFound.json"))
                .create();
    }

    public static void stubFinalGraphRoutes(final ProxyTestManager testManager) {
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifestQuery())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphManifestCode())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphManifestQuery.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphManifestFiltered())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphFilteredManifest())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphManifestFiltered.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNodesContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphCursorA())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodesContract.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdgesContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphEdgeCalls())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdgesContract.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphNodeContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphNodeDetailContract.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphEdgeContract())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("responseProxyGraphEdgeDetailContract.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphPageSizeInvalid())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphPageSizeZero())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.UNPROCESSABLE_ENTITY.value())
                        .responseBody("responseProxyGraphPageSizeInvalid.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphCursorInvalid())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphCursorMalformed())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.BAD_REQUEST.value())
                        .responseBody("responseProxyGraphCursorInvalid.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphSnapshotExpired())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphExpiredRevision())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.GONE.value())
                        .responseBody("responseProxyGraphSnapshotExpired.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphMissingNode())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.NOT_FOUND.value())
                        .responseBody("responseProxyGraphNodeNotFound.json"))
                .create();
        testManager.wiremock()
                .createMapping(InfrastructureProxyEndpoint.upstreamKnowledgeGraphMissingEdge())
                .urlWithQueryParam(InfrastructureProxyQuery.upstreamGraphSource())
                .applyDefault(context -> context
                        .responseStatus(HttpStatus.NOT_FOUND.value())
                        .responseBody("responseProxyGraphEdgeNotFound.json"))
                .create();
    }

    private InfrastructureProxyFixtures() {
    }
}
