package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;
import com.sitionix.forgeit.domain.endpoint.wiremock.WiremockDefault;
import org.springframework.http.HttpStatus;

public final class InfrastructureProxyEndpoint {

    public static Endpoint<Object, Object> actuatorHealth() {
        return nexusGet("/actuator/health", HttpStatus.OK, null);
    }

    public static Endpoint<Object, Object> nexusKnowledgeStatus() {
        return nexusGet("/api/v1/infrastructure/knowledge/status", "responseProxyKnowledgeStatus.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeSources() {
        return nexusGet("/api/v1/infrastructure/knowledge/sources", "responseProxyKnowledgeSources.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeOverview() {
        return nexusGet("/api/v1/infrastructure/knowledge/overview", "responseProxyKnowledgeOverview.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeInventoryBuild() {
        return nexusPost("/api/v1/infrastructure/knowledge/inventory/build", "requestProxyEmpty.json", "responseProxyInventoryBuild.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeInventoryBuildBody() {
        return nexusPost("/api/v1/infrastructure/knowledge/inventory/build", "requestProxyInventoryBuild.json", "responseProxyInventoryBuild.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeInventoryStatus() {
        return nexusGet("/api/v1/infrastructure/knowledge/inventory/status", "responseProxyInventoryStatus.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeInventoryFiles() {
        return nexusGet("/api/v1/infrastructure/knowledge/inventory/files", "responseProxyInventoryFiles.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisBuild() {
        return nexusPost("/api/v1/infrastructure/knowledge/analysis/build", "requestProxyEmpty.json", "responseProxyAnalysisBuild.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisBuildBody() {
        return nexusPost("/api/v1/infrastructure/knowledge/analysis/build", "requestProxyAnalysisBuild.json", "responseProxyAnalysisBuild.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisRetryFailed() {
        return nexusPost("/api/v1/infrastructure/knowledge/analysis/retry-failed", "requestProxyEmpty.json", "responseProxyAnalysisRetryFailed.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisJob() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/jobs/job-1", "responseProxyAnalysisJob.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisJobStop() {
        return nexusPost("/api/v1/infrastructure/knowledge/analysis/jobs/job-1/stop", "requestProxyEmpty.json", "responseProxyAnalysisJobStop.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisJobStopBody() {
        return nexusPost("/api/v1/infrastructure/knowledge/analysis/jobs/job-1/stop", "requestProxyAnalysisJobStop.json", "responseProxyAnalysisJobStop.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisStatus() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/status", "responseProxyAnalysisStatus.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisFiles() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/files", "responseProxyAnalysisFiles.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisDiagnostics() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/diagnostics", "responseProxyAnalysisDiagnostics.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphMetadata() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/metadata", "responseProxyGraphMetadata.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphManifest() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/manifest", "responseProxyGraphManifest.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphManifestFiltered() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/manifest", "responseProxyGraphManifestFiltered.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphManifestQuery() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/manifest", "responseProxyGraphManifestQuery.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphView() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/view", "responseProxyGraphView.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphViewFilterInvalid() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/view", HttpStatus.BAD_REQUEST, "responseProxyGraphViewFilterInvalid.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphNodes() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/nodes", "responseProxyGraphNodes.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphNodesContract() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/nodes", "responseProxyGraphNodesContract.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphEdges() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/edges", "responseProxyGraphEdges.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphEdgesContract() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/edges", "responseProxyGraphEdgesContract.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphNode() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/node/node-a", "responseProxyGraphNodeDetail.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphNodeContract() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/node/node-a", "responseProxyGraphNodeDetailContract.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphMissingNode() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/node/missing-node", HttpStatus.NOT_FOUND, "responseProxyGraphNodeNotFound.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphEdge() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/edge/edge-a", "responseProxyGraphEdgeDetail.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphEdgeContract() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/edge/edge-a", "responseProxyGraphEdgeDetailContract.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphMissingEdge() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/edge/missing-edge", HttpStatus.NOT_FOUND, "responseProxyGraphEdgeNotFound.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphPageSizeInvalid() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/nodes", HttpStatus.UNPROCESSABLE_ENTITY, "responseProxyGraphPageSizeInvalid.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphCursorInvalid() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/nodes", HttpStatus.BAD_REQUEST, "responseProxyGraphCursorInvalid.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeGraphRevisionStale() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/nodes", HttpStatus.GONE, "responseProxyGraphRevisionStale.json");
    }

    public static Endpoint<Object, Object> nexusJarvisStatus() {
        return nexusGet("/api/v1/infrastructure/jarvis/status", "responseProxyJarvisStatus.json");
    }

    public static Endpoint<Object, Object> nexusJarvisActions() {
        return nexusGet("/api/v1/infrastructure/jarvis/actions", "responseProxyJarvisActions.json");
    }

    public static Endpoint<Object, Object> nexusJarvisCommand() {
        return nexusPost("/api/v1/infrastructure/jarvis/command", "requestProxyJarvisCommand.json", "responseProxyJarvisCommand.json");
    }

    public static Endpoint<Object, Object> nexusJarvisQuery() {
        return nexusPost("/api/v1/infrastructure/jarvis/query", "requestProxyJarvisQuery.json", "responseProxyJarvisQuery.json");
    }

    public static Endpoint<Object, Object> nexusJarvisQueryNoCandidates() {
        return nexusPost("/api/v1/infrastructure/jarvis/query", "requestProxyJarvisQueryNoCandidates.json", "responseProxyJarvisQueryNoCandidates.json");
    }

    public static Endpoint<Object, Object> nexusJarvisQueryBlank() {
        return nexusPost("/api/v1/infrastructure/jarvis/query", "requestProxyJarvisQueryBlank.json", HttpStatus.BAD_REQUEST, null);
    }

    public static Endpoint<Object, Object> nexusUnsupportedKnowledgeGraph() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph", HttpStatus.NOT_FOUND, null);
    }

    public static Endpoint<Object, Object> nexusUnsupportedKnowledgeGraphSlice() {
        return nexusGet("/api/v1/infrastructure/knowledge/analysis/graph/slice", HttpStatus.NOT_FOUND, null);
    }

    public static Endpoint<Object, Object> nexusOpenProxyRejected() {
        return nexusGet("/api/v1/infrastructure/proxy/knowledge/status", HttpStatus.NOT_FOUND, null);
    }

    public static Endpoint<Object, Object> nexusKnowledgeStatusTimeout() {
        return nexusGet("/api/v1/infrastructure/knowledge/status", HttpStatus.GATEWAY_TIMEOUT, "responseProxyErrorKnowledgeTimeout.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeStatusResponseTooLarge() {
        return nexusGet("/api/v1/infrastructure/knowledge/status", HttpStatus.BAD_GATEWAY, "responseProxyErrorKnowledgeResponseTooLarge.json");
    }

    public static Endpoint<Object, Object> nexusJarvisStatusResponseTooLarge() {
        return nexusGet("/api/v1/infrastructure/jarvis/status", HttpStatus.BAD_GATEWAY, "responseProxyErrorJarvisResponseTooLarge.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeStatusNonJson() {
        return nexusGet("/api/v1/infrastructure/knowledge/status", HttpStatus.BAD_GATEWAY, "responseProxyErrorKnowledgeNonJson.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeStatusUpstreamServerError() {
        return nexusGet("/api/v1/infrastructure/knowledge/status", HttpStatus.BAD_GATEWAY, "responseProxyErrorKnowledgeServerError.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeAnalysisBuildRequestTooLarge() {
        return nexusPost("/api/v1/infrastructure/knowledge/analysis/build", "requestProxyOversized.json", HttpStatus.PAYLOAD_TOO_LARGE,
                "responseProxyErrorKnowledgeRequestTooLarge.json");
    }

    public static Endpoint<Object, Object> nexusJarvisQueryRequestTooLarge() {
        return nexusPost("/api/v1/infrastructure/jarvis/query", "requestProxyJarvisQueryOversized.json", HttpStatus.PAYLOAD_TOO_LARGE,
                "responseProxyErrorJarvisRequestTooLarge.json");
    }

    public static Endpoint<Object, Object> nexusJarvisQueryUpstreamServerError() {
        return nexusPost("/api/v1/infrastructure/jarvis/query", "requestProxyJarvisQuery.json", HttpStatus.BAD_GATEWAY,
                "responseProxyErrorJarvisQueryServerError.json");
    }

    public static Endpoint<Object, Object> nexusJarvisQueryTimeout() {
        return nexusPost("/api/v1/infrastructure/jarvis/query", "requestProxyJarvisQuery.json", HttpStatus.GATEWAY_TIMEOUT,
                "responseProxyErrorJarvisQueryTimeout.json");
    }

    public static Endpoint<Object, Object> nexusJarvisQueryConnectionRefused() {
        return nexusPost("/api/v1/infrastructure/jarvis/query", "requestProxyJarvisQuery.json", HttpStatus.SERVICE_UNAVAILABLE,
                "responseProxyErrorJarvisQueryConnectionRefused.json");
    }

    public static Endpoint<Object, Object> nexusJarvisCommandUpstreamServerError() {
        return nexusPost("/api/v1/infrastructure/jarvis/command", "requestProxyJarvisCommand.json", HttpStatus.BAD_GATEWAY,
                "responseProxyErrorJarvisCommandServerError.json");
    }

    public static Endpoint<Object, Object> nexusKnowledgeStatusConnectionRefused() {
        return nexusGet("/api/v1/infrastructure/knowledge/status", HttpStatus.SERVICE_UNAVAILABLE, "responseProxyErrorKnowledgeConnectionRefused.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeStatus() {
        return upstreamGet("/api/v1/knowledge/status", "responseProxyKnowledgeStatus.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeSources() {
        return upstreamGet("/api/v1/knowledge/sources", "responseProxyKnowledgeSources.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeOverview() {
        return upstreamGet("/api/v1/knowledge/overview", "responseProxyKnowledgeOverview.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeInventoryBuild() {
        return upstreamPost("/api/v1/knowledge/inventory/build", "requestProxyEmpty.json", "responseProxyInventoryBuild.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeInventoryBuildBody() {
        return upstreamPost("/api/v1/knowledge/inventory/build", "requestProxyInventoryBuild.json", "responseProxyInventoryBuild.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeInventoryStatus() {
        return upstreamGet("/api/v1/knowledge/inventory/status", "responseProxyInventoryStatus.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeInventoryFiles() {
        return upstreamGet("/api/v1/knowledge/inventory/files", "responseProxyInventoryFiles.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeAnalysisBuild() {
        return upstreamPost("/api/v1/knowledge/analysis/build", "requestProxyEmpty.json", "responseProxyAnalysisBuild.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeAnalysisBuildBody() {
        return upstreamPost("/api/v1/knowledge/analysis/build", "requestProxyAnalysisBuild.json", "responseProxyAnalysisBuild.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeAnalysisRetryFailed() {
        return upstreamPost("/api/v1/knowledge/analysis/retry-failed", "requestProxyEmpty.json", "responseProxyAnalysisRetryFailed.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeAnalysisJob() {
        return upstreamGet("/api/v1/knowledge/analysis/jobs/job-1", "responseProxyAnalysisJob.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeAnalysisJobStop() {
        return upstreamPost("/api/v1/knowledge/analysis/jobs/job-1/stop", "requestProxyEmpty.json", "responseProxyAnalysisJobStop.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeAnalysisJobStopBody() {
        return upstreamPost("/api/v1/knowledge/analysis/jobs/job-1/stop", "requestProxyAnalysisJobStop.json", "responseProxyAnalysisJobStop.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeAnalysisStatus() {
        return upstreamGet("/api/v1/knowledge/analysis/status", "responseProxyAnalysisStatus.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeAnalysisFiles() {
        return upstreamGet("/api/v1/knowledge/analysis/files", "responseProxyAnalysisFiles.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeAnalysisDiagnostics() {
        return upstreamGet("/api/v1/knowledge/analysis/diagnostics", "responseProxyAnalysisDiagnostics.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphMetadata() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/metadata", "responseProxyGraphMetadata.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphManifest() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/manifest", "responseProxyGraphManifest.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphManifestFiltered() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/manifest", "responseProxyGraphManifestFiltered.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphManifestQuery() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/manifest", "responseProxyGraphManifestQuery.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphView() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/view", "responseProxyGraphView.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphViewFilterInvalid() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/view", HttpStatus.BAD_REQUEST, "responseProxyGraphViewFilterInvalid.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphNodes() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/nodes", "responseProxyGraphNodes.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphNodesContract() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/nodes", "responseProxyGraphNodesContract.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphEdges() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/edges", "responseProxyGraphEdges.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphEdgesContract() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/edges", "responseProxyGraphEdgesContract.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphNode() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/node/node-a", "responseProxyGraphNodeDetail.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphNodeContract() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/node/node-a", "responseProxyGraphNodeDetailContract.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphMissingNode() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/node/missing-node", HttpStatus.NOT_FOUND, "responseProxyGraphNodeNotFound.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphEdge() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/edge/edge-a", "responseProxyGraphEdgeDetail.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphEdgeContract() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/edge/edge-a", "responseProxyGraphEdgeDetailContract.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphMissingEdge() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/edge/missing-edge", HttpStatus.NOT_FOUND, "responseProxyGraphEdgeNotFound.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphPageSizeInvalid() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/nodes", HttpStatus.UNPROCESSABLE_ENTITY, "responseProxyGraphPageSizeInvalid.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphCursorInvalid() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/nodes", HttpStatus.BAD_REQUEST, "responseProxyGraphCursorInvalid.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeGraphRevisionStale() {
        return upstreamGet("/api/v1/knowledge/analysis/graph/nodes", HttpStatus.GONE, "responseProxyGraphRevisionStale.json");
    }

    public static Endpoint<Object, Object> upstreamJarvisStatus() {
        return upstreamGet("/api/v1/jarvis/status", "responseProxyJarvisStatus.json");
    }

    public static Endpoint<Object, Object> upstreamJarvisActions() {
        return upstreamGet("/api/v1/jarvis/actions", "responseProxyJarvisActions.json");
    }

    public static Endpoint<Object, Object> upstreamJarvisCommand() {
        return upstreamPost("/api/v1/jarvis/command", "requestProxyJarvisCommand.json", "responseProxyJarvisCommand.json");
    }

    public static Endpoint<Object, Object> upstreamJarvisQuery() {
        return upstreamPost("/api/v1/jarvis/query", "requestProxyJarvisQuery.json", "responseProxyJarvisQuery.json");
    }

    public static Endpoint<Object, Object> upstreamJarvisQueryNoCandidates() {
        return upstreamPost("/api/v1/jarvis/query", "requestProxyJarvisQueryNoCandidates.json", "responseProxyJarvisQueryNoCandidates.json");
    }

    public static Endpoint<Object, Object> upstreamTooLargeKnowledgeStatus() {
        return upstreamGet("/api/v1/knowledge/status", "responseProxyTooLarge.json");
    }

    public static Endpoint<Object, Object> upstreamTooLargeJarvisStatus() {
        return upstreamGet("/api/v1/jarvis/status", "responseProxyTooLarge.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeStatusNonJson() {
        return upstreamGet("/api/v1/knowledge/status", "responseProxyInvalidJson.json");
    }

    public static Endpoint<Object, Object> upstreamKnowledgeStatusServerError() {
        return upstreamGet("/api/v1/knowledge/status", HttpStatus.INTERNAL_SERVER_ERROR, "responseProxyUpstreamServerError.json");
    }

    public static Endpoint<Object, Object> upstreamJarvisQueryServerError() {
        return upstreamPost("/api/v1/jarvis/query", "requestProxyJarvisQuery.json", HttpStatus.INTERNAL_SERVER_ERROR, "responseProxyUpstreamServerError.json");
    }

    public static Endpoint<Object, Object> upstreamJarvisQueryTimeout() {
        return upstreamPost("/api/v1/jarvis/query", "requestProxyJarvisQuery.json", "responseProxyJarvisQuery.json");
    }

    public static Endpoint<Object, Object> upstreamJarvisCommandServerError() {
        return upstreamPost("/api/v1/jarvis/command", "requestProxyJarvisCommand.json", HttpStatus.INTERNAL_SERVER_ERROR, "responseProxyUpstreamServerError.json");
    }

    private static Endpoint<Object, Object> nexusGet(final String path, final String responseFixture) {
        return nexusGet(path, HttpStatus.OK, responseFixture);
    }

    private static Endpoint<Object, Object> nexusGet(final String path,
                                                     final HttpStatus status,
                                                     final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.GET, Object.class, Object.class,
                (MockmvcDefault) context -> {
                    context.expectStatus(status.value());
                    if (responseFixture != null) {
                        context.expectResponse(responseFixture);
                    }
                });
    }

    private static Endpoint<Object, Object> nexusPost(final String path,
                                                      final String requestFixture,
                                                      final String responseFixture) {
        return nexusPost(path, requestFixture, HttpStatus.OK, responseFixture);
    }

    private static Endpoint<Object, Object> nexusPost(final String path,
                                                      final String requestFixture,
                                                      final HttpStatus status,
                                                      final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.POST, Object.class, Object.class,
                (MockmvcDefault) context -> {
                    context.withRequest(requestFixture).expectStatus(status.value());
                    if (responseFixture != null) {
                        context.expectResponse(responseFixture);
                    }
                });
    }

    private static Endpoint<Object, Object> upstreamGet(final String path, final String responseFixture) {
        return upstreamGet(path, HttpStatus.OK, responseFixture);
    }

    private static Endpoint<Object, Object> upstreamGet(final String path,
                                                       final HttpStatus status,
                                                       final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.GET, Object.class, Object.class,
                (WiremockDefault) context -> context.plainUrl()
                        .responseStatus(status.value())
                        .responseBody(responseFixture));
    }

    private static Endpoint<Object, Object> upstreamPost(final String path,
                                                        final String requestFixture,
                                                        final String responseFixture) {
        return upstreamPost(path, requestFixture, HttpStatus.OK, responseFixture);
    }

    private static Endpoint<Object, Object> upstreamPost(final String path,
                                                        final String requestFixture,
                                                        final HttpStatus status,
                                                        final String responseFixture) {
        return Endpoint.createContract(path, HttpMethod.POST, Object.class, Object.class,
                (WiremockDefault) context -> context.plainUrl()
                        .matchesJson(requestFixture)
                        .responseStatus(status.value())
                        .responseBody(responseFixture));
    }

    private InfrastructureProxyEndpoint() {
    }
}
