package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeit.mockmvc.api.QueryParams;
import com.sitionix.forgeit.wiremock.api.WireMockQueryParams;

public final class InfrastructureProxyQuery {

    public static QueryParams inventoryJavaExtension() {
        return QueryParams.create().add("extension", ".java");
    }

    public static QueryParams traceOne() {
        return QueryParams.create().add("trace", "1");
    }

    public static QueryParams slowCase() {
        return QueryParams.create().add("case", "slow");
    }

    public static QueryParams timeoutCase() {
        return QueryParams.create().add("case", "timeout");
    }

    public static QueryParams responseTooLargeCase() {
        return QueryParams.create().add("case", "response-too-large");
    }

    public static QueryParams nonJsonCase() {
        return QueryParams.create().add("case", "non-json");
    }

    public static QueryParams serverErrorCase() {
        return QueryParams.create().add("case", "server-error");
    }

    public static QueryParams humanContextBudgetCase() {
        return QueryParams.create().add("case", "human-context-budget");
    }

    public static QueryParams humanTimeoutCase() {
        return QueryParams.create().add("case", "human-timeout");
    }

    public static QueryParams humanGenerationFailedCase() {
        return QueryParams.create().add("case", "human-generation-failed");
    }

    public static QueryParams graphRevisionA() {
        return QueryParams.create().add("graphRevision", "rev-a");
    }

    public static QueryParams graphFilteredManifest() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE")
                .add("factOrigin", "STATIC")
                .add("nodeKind", "CALLABLE")
                .add("edgeType", "CALLS")
                .add("includeExternal", "hide")
                .add("includeUnresolved", "false")
                .add("includeIsolated", "false");
    }

    public static QueryParams graphManifestCode() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE");
    }

    public static QueryParams graphMetadataSource() {
        return QueryParams.create().add("sourceId", "forge-ai");
    }

    public static QueryParams graphViewContract() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE")
                .add("includeExternal", "hide")
                .add("includeUnresolved", "false")
                .add("includeIsolated", "true")
                .add("search", "Name")
                .add("maxNodes", "80");
    }

    public static QueryParams graphViewInvalidFilter() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("includeExternal", "collapsed")
                .add("maxNodes", "20");
    }

    public static QueryParams graphNodesContract() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE")
                .add("graphRevision", "rev-a")
                .add("cursor", "cursor-a")
                .add("pageSize", "5");
    }

    public static QueryParams graphEdgesContract() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE")
                .add("edgeType", "CALLS")
                .add("includeUnresolved", "false")
                .add("graphRevision", "rev-a")
                .add("pageSize", "5");
    }

    public static QueryParams graphNodeDetailContract() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("graphRevision", "rev-a")
                .add("includeEvidence", "true");
    }

    public static QueryParams graphPageSizeInvalid() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE")
                .add("graphRevision", "rev-a")
                .add("pageSize", "0");
    }

    public static QueryParams graphCursorInvalid() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE")
                .add("graphRevision", "rev-a")
                .add("cursor", "malformed");
    }

    public static QueryParams graphExpiredRevision() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE")
                .add("graphRevision", "expired");
    }

    public static QueryParams graphMissingDetail() {
        return QueryParams.create()
                .add("sourceId", "forge-ai")
                .add("graphRevision", "rev-a");
    }

    public static WireMockQueryParams upstreamInventoryJavaExtension() {
        return WireMockQueryParams.create().add("extension", ".java");
    }

    public static WireMockQueryParams upstreamTraceOne() {
        return WireMockQueryParams.create().add("trace", "1");
    }

    public static WireMockQueryParams upstreamSlowCase() {
        return WireMockQueryParams.create().add("case", "slow");
    }

    public static WireMockQueryParams upstreamTimeoutCase() {
        return WireMockQueryParams.create().add("case", "timeout");
    }

    public static WireMockQueryParams upstreamResponseTooLargeCase() {
        return WireMockQueryParams.create().add("case", "response-too-large");
    }

    public static WireMockQueryParams upstreamNonJsonCase() {
        return WireMockQueryParams.create().add("case", "non-json");
    }

    public static WireMockQueryParams upstreamServerErrorCase() {
        return WireMockQueryParams.create().add("case", "server-error");
    }

    public static WireMockQueryParams upstreamHumanContextBudgetCase() {
        return WireMockQueryParams.create().add("case", "human-context-budget");
    }

    public static WireMockQueryParams upstreamHumanTimeoutCase() {
        return WireMockQueryParams.create().add("case", "human-timeout");
    }

    public static WireMockQueryParams upstreamHumanGenerationFailedCase() {
        return WireMockQueryParams.create().add("case", "human-generation-failed");
    }

    public static WireMockQueryParams upstreamGraphRevisionA() {
        return WireMockQueryParams.create().add("graphRevision", "rev-a");
    }

    public static WireMockQueryParams upstreamGraphFilteredManifest() {
        return WireMockQueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE")
                .add("factOrigin", "STATIC")
                .add("nodeKind", "CALLABLE")
                .add("edgeType", "CALLS")
                .add("includeExternal", "hide")
                .add("includeUnresolved", false)
                .add("includeIsolated", false);
    }

    public static WireMockQueryParams upstreamGraphManifestCode() {
        return WireMockQueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE");
    }

    public static WireMockQueryParams upstreamGraphMetadataSource() {
        return WireMockQueryParams.create().add("sourceId", "forge-ai");
    }

    public static WireMockQueryParams upstreamGraphViewContract() {
        return WireMockQueryParams.create()
                .add("sourceId", "forge-ai")
                .add("flowDomain", "CODE")
                .add("includeExternal", "hide")
                .add("includeUnresolved", false)
                .add("includeIsolated", true)
                .add("search", "Name")
                .add("maxNodes", "80");
    }

    public static WireMockQueryParams upstreamGraphViewInvalidFilter() {
        return WireMockQueryParams.create()
                .add("sourceId", "forge-ai")
                .add("includeExternal", "collapsed")
                .add("maxNodes", "20");
    }

    public static WireMockQueryParams upstreamGraphCursorA() {
        return WireMockQueryParams.create().add("cursor", "cursor-a");
    }

    public static WireMockQueryParams upstreamGraphEdgeCalls() {
        return WireMockQueryParams.create().add("edgeType", "CALLS");
    }

    public static WireMockQueryParams upstreamGraphSource() {
        return WireMockQueryParams.create().add("sourceId", "forge-ai");
    }

    public static WireMockQueryParams upstreamGraphPageSizeZero() {
        return WireMockQueryParams.create().add("pageSize", "0");
    }

    public static WireMockQueryParams upstreamGraphCursorMalformed() {
        return WireMockQueryParams.create().add("cursor", "malformed");
    }

    public static WireMockQueryParams upstreamGraphExpiredRevision() {
        return WireMockQueryParams.create().add("graphRevision", "expired");
    }

    private InfrastructureProxyQuery() {
    }
}
