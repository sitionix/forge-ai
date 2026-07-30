package com.sitionix.forgeai.api.proxy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

@Component
public class InfrastructureProxyRouteRegistry {

    private final Map<String, InfrastructureProxyRoute> routes;

    @Autowired
    public InfrastructureProxyRouteRegistry(final InfrastructureProxyProperties properties,
                                            final ForgeAiHumanQueryProperties humanQueryProperties) {
        final Map<String, InfrastructureProxyRoute> registered = new LinkedHashMap<>();
        final Duration humanQueryDeadline = humanQueryProperties.requestTimeout();
        final var humanQueryReadTimeout = properties.getProxy().humanQueryReadTimeout(humanQueryDeadline);
        this.knowledge(registered, "knowledge.status", HttpMethod.GET, "/api/v1/knowledge/status", false);
        this.knowledge(registered, "knowledge.sources", HttpMethod.GET, "/api/v1/knowledge/sources", false);
        this.knowledge(registered, "knowledge.ai-runtime", HttpMethod.GET, "/api/v1/knowledge/ai-runtime", false);
        this.knowledge(registered, "knowledge.active-profile", HttpMethod.GET, "/api/v1/knowledge/active-profile", false);
        this.knowledge(registered, "knowledge.active-profile.llm-profile", HttpMethod.PUT,
                "/api/v1/knowledge/active-profile/llm-profile", true);
        this.knowledge(registered, "knowledge.overview", HttpMethod.GET, "/api/v1/knowledge/overview", false);
        this.knowledge(registered, "knowledge.inventory.build", HttpMethod.POST, "/api/v1/knowledge/inventory/build", true);
        this.knowledge(registered, "knowledge.inventory.status", HttpMethod.GET, "/api/v1/knowledge/inventory/status", false);
        this.knowledge(registered, "knowledge.inventory.files", HttpMethod.GET, "/api/v1/knowledge/inventory/files", false);
        this.knowledge(
                registered,
                "knowledge.query",
                HttpMethod.POST,
                "/api/v1/knowledge/query",
                true,
                humanQueryReadTimeout
        );
        this.knowledge(
                registered,
                "knowledge.query.tool-context",
                HttpMethod.POST,
                "/api/v1/knowledge/query/tool-context",
                true,
                humanQueryReadTimeout
        );
        this.knowledge(registered, "knowledge.analysis.build", HttpMethod.POST, "/api/v1/knowledge/analysis/build", true);
        this.knowledge(registered, "knowledge.analysis.retry-failed", HttpMethod.POST, "/api/v1/knowledge/analysis/retry-failed", true);
        this.knowledge(registered, "knowledge.analysis.job", HttpMethod.GET,
                vars -> "/api/v1/knowledge/analysis/jobs/" + segment(vars.get("jobId")), false);
        this.knowledge(registered, "knowledge.analysis.job.stop", HttpMethod.POST,
                vars -> "/api/v1/knowledge/analysis/jobs/" + segment(vars.get("jobId")) + "/stop", true);
        this.knowledge(registered, "knowledge.analysis.status", HttpMethod.GET, "/api/v1/knowledge/analysis/status", false);
        this.knowledge(registered, "knowledge.analysis.current-file-progress", HttpMethod.GET,
                "/api/v1/knowledge/analysis/current-file-progress", false);
        this.knowledge(registered, "knowledge.analysis.files", HttpMethod.GET, "/api/v1/knowledge/analysis/files", false);
        this.knowledge(registered, "knowledge.analysis.diagnostics", HttpMethod.GET, "/api/v1/knowledge/analysis/diagnostics", false);
        this.knowledge(registered, "knowledge.graph.metadata", HttpMethod.GET, "/api/v1/knowledge/analysis/graph/metadata", false);
        this.knowledge(registered, "knowledge.graph.manifest", HttpMethod.GET, "/api/v1/knowledge/analysis/graph/manifest", false);
        this.knowledge(registered, "knowledge.graph.view", HttpMethod.GET, "/api/v1/knowledge/analysis/graph/view", false);
        this.knowledge(registered, "knowledge.graph.nodes", HttpMethod.GET, "/api/v1/knowledge/analysis/graph/nodes", false);
        this.knowledge(registered, "knowledge.graph.edges", HttpMethod.GET, "/api/v1/knowledge/analysis/graph/edges", false);
        this.knowledge(registered, "knowledge.graph.node", HttpMethod.GET,
                vars -> "/api/v1/knowledge/analysis/graph/node/" + segment(vars.get("nodeId")), false);
        this.knowledge(registered, "knowledge.graph.edge", HttpMethod.GET,
                vars -> "/api/v1/knowledge/analysis/graph/edge/" + segment(vars.get("edgeId")), false);

        this.jarvis(registered, "jarvis.status", HttpMethod.GET, "/api/v1/jarvis/status", false);
        this.jarvis(registered, "jarvis.actions", HttpMethod.GET, "/api/v1/jarvis/actions", false);
        this.jarvis(registered, "jarvis.command", HttpMethod.POST, "/api/v1/jarvis/command", true);
        this.jarvis(registered, "jarvis.query", HttpMethod.POST, "/api/v1/jarvis/query", true, humanQueryReadTimeout, true);
        this.routes = Map.copyOf(registered);
    }

    InfrastructureProxyRouteRegistry(final InfrastructureProxyProperties properties) {
        this(properties, new ForgeAiHumanQueryProperties());
    }

    public InfrastructureProxyRoute require(final String key) {
        final InfrastructureProxyRoute route = this.routes.get(key);
        if (route == null) {
            throw new InfrastructureProxyRouteException(key);
        }
        return route;
    }

    private void knowledge(final Map<String, InfrastructureProxyRoute> registered,
                           final String key,
                           final HttpMethod method,
                           final String upstreamPath,
                           final boolean requestBodyAllowed) {
        this.knowledge(registered, key, method, ignored -> upstreamPath, requestBodyAllowed, null);
    }

    private void knowledge(final Map<String, InfrastructureProxyRoute> registered,
                           final String key,
                           final HttpMethod method,
                           final String upstreamPath,
                           final boolean requestBodyAllowed,
                           final Duration readTimeout) {
        this.knowledge(registered, key, method, ignored -> upstreamPath, requestBodyAllowed, readTimeout);
    }

    private void knowledge(final Map<String, InfrastructureProxyRoute> registered,
                           final String key,
                           final HttpMethod method,
                           final java.util.function.Function<Map<String, String>, String> upstreamPath,
                           final boolean requestBodyAllowed) {
        this.knowledge(registered, key, method, upstreamPath, requestBodyAllowed, null);
    }

    private void knowledge(final Map<String, InfrastructureProxyRoute> registered,
                           final String key,
                           final HttpMethod method,
                           final java.util.function.Function<Map<String, String>, String> upstreamPath,
                           final boolean requestBodyAllowed,
                           final Duration readTimeout) {
        registered.put(key, new InfrastructureProxyRoute(
                key,
                InfrastructureProxyService.KNOWLEDGE,
                method,
                upstreamPath,
                requestBodyAllowed,
                true,
                readTimeout,
                false
        ));
    }

    private void jarvis(final Map<String, InfrastructureProxyRoute> registered,
                        final String key,
                        final HttpMethod method,
                        final String upstreamPath,
                        final boolean requestBodyAllowed) {
        this.jarvis(registered, key, method, upstreamPath, requestBodyAllowed, null);
    }

    private void jarvis(final Map<String, InfrastructureProxyRoute> registered,
                        final String key,
                        final HttpMethod method,
                        final String upstreamPath,
                        final boolean requestBodyAllowed,
                        final Duration readTimeout) {
        this.jarvis(registered, key, method, upstreamPath, requestBodyAllowed, readTimeout, false);
    }

    private void jarvis(final Map<String, InfrastructureProxyRoute> registered,
                        final String key,
                        final HttpMethod method,
                        final String upstreamPath,
                        final boolean requestBodyAllowed,
                        final Duration readTimeout,
                        final boolean preserveControlledUpstreamErrors) {
        registered.put(key, new InfrastructureProxyRoute(
                key,
                InfrastructureProxyService.JARVIS,
                method,
                ignored -> upstreamPath,
                requestBodyAllowed,
                true,
                readTimeout,
                preserveControlledUpstreamErrors
        ));
    }

    private static String segment(final String value) {
        return UriUtils.encodePathSegment(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
