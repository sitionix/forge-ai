package com.sitionix.forgeai.application.laneexecution.orchestration;

import com.sitionix.forgeai.domain.model.codex.ContractRefContext;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.codex.ServiceScopeContext;
import com.sitionix.forgeai.domain.model.generation.ApiArtifactGenerationRequest;
import com.sitionix.forgeai.domain.model.generation.GeneratedApiArtifact;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import com.sitionix.forgeai.domain.port.ApiArtifactGenerationPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiArtifactGenerationOrchestratorTest {

    @Test
    void givenLocalRepositoryEvidenceAndPrUrl_whenExecute_thenUseGithubRepositoryFromPrUrl() {
        final RecordingApiArtifactGenerationPort generationPort = new RecordingApiArtifactGenerationPort();
        final ApiArtifactGenerationOrchestrator orchestrator = new ApiArtifactGenerationOrchestrator(generationPort);
        final ApiArtifactGenerationOrchestratorInput input = new ApiArtifactGenerationOrchestratorInput(
                UUID.randomUUID(),
                "SITIONIX-142",
                UUID.randomUUID(),
                "api",
                "GLOBAL",
                "global",
                "generation",
                "apiArtifactGeneration",
                List.of(),
                this.scopeContext(),
                Map.of(),
                Map.of(
                        "preparation", Map.of("repository", "/workspace/app-afesox"),
                        "pr", Map.of("prUrl", "https://github.com/sitionix/app-afesox/pull/174")
                )
        );

        final LaneStepDoneResult result = orchestrator.execute(null, input);

        assertThat(generationPort.requests())
                .extracting(ApiArtifactGenerationRequest::repository)
                .containsOnly("sitionix/app-afesox");
        assertThat(result.getEvidence())
                .containsEntry("repo", "sitionix/app-afesox")
                .containsEntry("generatedArtifactCount", 2);
    }

    private ScopeContext scopeContext() {
        return ScopeContext.builder()
                .scope("GLOBAL")
                .relatedServices(Set.of(ServiceScopeContext.builder()
                        .serviceId("bffssox")
                        .scope("backendforfrontendservice-sox")
                        .path("backendforfrontendservice-sox")
                        .label("Backend for Frontend Service SOX")
                        .contractRefs(Map.of("api", ContractRefContext.builder()
                                .sourceRepo("app-afesox")
                                .apiFamily("bffssox")
                                .serviceCode("bffssox")
                                .generatedArtifacts(List.of("app-afesox-bffssox-api-first-stable"))
                                .consumerArtifacts(List.of())
                                .frontendPackages(List.of("@sitionix/app-afesox-bffssox-frontend-stable"))
                                .build()))
                        .build()))
                .build();
    }

    private static final class RecordingApiArtifactGenerationPort implements ApiArtifactGenerationPort {

        private final List<ApiArtifactGenerationRequest> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public GeneratedApiArtifact generate(final ApiArtifactGenerationRequest request) {
            this.requests.add(request);
            return new GeneratedApiArtifact(
                    request.generationType(),
                    request.scope(),
                    request.expectedArtifact().replace("-stable", "-sitionix-it-unstable"),
                    1L,
                    "https://github.com/sitionix/app-afesox/actions/runs/1",
                    List.of("test")
            );
        }

        private List<ApiArtifactGenerationRequest> requests() {
            synchronized (this.requests) {
                return List.copyOf(this.requests);
            }
        }
    }
}
