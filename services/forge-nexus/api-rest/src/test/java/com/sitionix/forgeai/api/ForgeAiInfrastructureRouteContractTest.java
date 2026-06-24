package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

class ForgeAiInfrastructureRouteContractTest {

    @Test
    void knowledgeAndJarvisInfrastructureRoutesAreExplicitlyAllowlisted() {
        assertThat(routes(ForgeAiInfrastructureKnowledgeController.class, ForgeAiInfrastructureJarvisController.class))
                .containsExactlyInAnyOrder(
                        "GET /api/v1/infrastructure/jarvis/actions",
                        "GET /api/v1/infrastructure/jarvis/status",
                        "GET /api/v1/infrastructure/knowledge/analysis/files",
                        "GET /api/v1/infrastructure/knowledge/analysis/diagnostics",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/edge/{edgeId}",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/edges",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/manifest",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/metadata",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/node/{nodeId}",
                        "GET /api/v1/infrastructure/knowledge/analysis/graph/nodes",
                        "GET /api/v1/infrastructure/knowledge/analysis/jobs/{jobId}",
                        "GET /api/v1/infrastructure/knowledge/analysis/status",
                        "GET /api/v1/infrastructure/knowledge/inventory/files",
                        "GET /api/v1/infrastructure/knowledge/inventory/status",
                        "GET /api/v1/infrastructure/knowledge/overview",
                        "GET /api/v1/infrastructure/knowledge/sources",
                        "GET /api/v1/infrastructure/knowledge/status",
                        "POST /api/v1/infrastructure/jarvis/chat",
                        "POST /api/v1/infrastructure/jarvis/command",
                        "POST /api/v1/infrastructure/knowledge/analysis/build",
                        "POST /api/v1/infrastructure/knowledge/analysis/jobs/{jobId}/stop",
                        "POST /api/v1/infrastructure/knowledge/analysis/retry-failed",
                        "POST /api/v1/infrastructure/knowledge/inventory/build"
                );
    }

    @SafeVarargs
    private static Set<String> routes(final Class<?>... controllers) {
        final Set<String> result = new TreeSet<>();
        for (final Class<?> controller : controllers) {
            for (final Method method : controller.getDeclaredMethods()) {
                final GetMapping get = method.getAnnotation(GetMapping.class);
                if (get != null) {
                    Arrays.stream(get.value()).map(path -> "GET " + path).forEach(result::add);
                }
                final PostMapping post = method.getAnnotation(PostMapping.class);
                if (post != null) {
                    Arrays.stream(post.value()).map(path -> "POST " + path).forEach(result::add);
                }
            }
        }
        return result;
    }
}
