package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class ForgeAiInfrastructureRouteContractTest {

    @Test
    void knowledgeAndJarvisInfrastructureRoutesAreExplicitlyAllowlisted() {
        assertThat(routes())
                .containsExactlyInAnyOrder(
                        "GET /api/v1/infrastructure/jarvis/actions",
                        "GET /api/v1/infrastructure/jarvis/status",
                        "GET /api/v1/infrastructure/knowledge/analysis/files",
                        "GET /api/v1/infrastructure/knowledge/analysis/current-file-progress",
                        "GET /api/v1/infrastructure/knowledge/analysis/diagnostics",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/edge/{edgeId}",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/edges",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/manifest",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/metadata",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/node/{nodeId}",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/nodes",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/view",
                        "GET /api/v1/infrastructure/knowledge/analysis/jobs/{jobId}",
                        "GET /api/v1/infrastructure/knowledge/analysis/status",
                        "GET /api/v1/infrastructure/knowledge/active-profile",
                        "GET /api/v1/infrastructure/knowledge/ai-runtime",
                        "GET /api/v1/infrastructure/knowledge/inventory/files",
                        "GET /api/v1/infrastructure/knowledge/inventory/status",
                        "GET /api/v1/infrastructure/knowledge/overview",
                        "GET /api/v1/infrastructure/knowledge/sources",
                        "GET /api/v1/infrastructure/knowledge/status",
                        "POST /api/v1/infrastructure/jarvis/command",
                        "POST /api/v1/infrastructure/jarvis/query",
                        "POST /api/v1/infrastructure/knowledge/analysis/build",
                        "POST /api/v1/infrastructure/knowledge/analysis/jobs/{jobId}/stop",
                        "POST /api/v1/infrastructure/knowledge/analysis/retry-failed",
                        "POST /api/v1/infrastructure/knowledge/inventory/build",
                        "POST /api/v1/infrastructure/knowledge/query",
                        "POST /api/v1/infrastructure/knowledge/query/tool-context",
                        "PUT /api/v1/infrastructure/knowledge/active-profile/llm-profile"
                );
    }

    private static Set<String> routes() {
        final GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(InfrastructureProxyTransport.class, () -> mock(InfrastructureProxyTransport.class));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(ForgeAiInfrastructureKnowledgeController.class);
        context.registerBean(ForgeAiInfrastructureJarvisController.class);
        context.refresh();
        try {
            final RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();
            final Set<String> result = new TreeSet<>();
            mapping.getHandlerMethods().forEach((info, ignored) -> {
                final Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                final Set<String> paths = info.getPathPatternsCondition() != null
                        ? info.getPathPatternsCondition().getPatternValues()
                        : info.getPatternsCondition().getPatterns();
                for (final RequestMethod method : methods) {
                    paths.stream().map(path -> method.name() + " " + path).forEach(result::add);
                }
            });
            return result;
        } finally {
            context.close();
        }
    }
}
