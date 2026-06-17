package com.sitionix.forgeai.application.laneexecution.orchestration;

import com.sitionix.forgeai.domain.model.codex.ContractRefContext;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.codex.ServiceScopeContext;
import com.sitionix.forgeai.domain.model.generation.ApiArtifactGenerationRequest;
import com.sitionix.forgeai.domain.model.generation.GeneratedApiArtifact;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import com.sitionix.forgeai.domain.port.ApiArtifactGenerationPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@LaneStepOrchestrator(value = "apiArtifactGeneration", input = ApiArtifactGenerationOrchestratorInput.class)
public class ApiArtifactGenerationOrchestrator implements LaneStepOrchestratorHandler<ApiArtifactGenerationOrchestratorInput> {

    private static final String API_CONTRACT_REF = "api";

    private final ApiArtifactGenerationPort apiArtifactGenerationPort;

    @Override
    public LaneStepDoneResult execute(final LaneStepOrchestratorContext context,
                                      final ApiArtifactGenerationOrchestratorInput input) {
        final List<ApiArtifactGenerationTarget> targets = this.resolveTargets(input);
        final String pullRequestUrl = this.requirePullRequestUrl(input);
        final String repository = this.requireRepository(input, pullRequestUrl);
        final List<ApiArtifactGenerationRequest> requests = this.requests(targets, pullRequestUrl, repository);
        final List<GeneratedApiArtifact> generatedArtifacts = this.generateAll(requests);
        final Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("orchestrator", input.handler());
        evidence.put("targetCount", targets.size());
        evidence.put("targets", targets.stream().map(this::targetEvidence).toList());
        evidence.put("prUrl", pullRequestUrl);
        evidence.put("repo", repository);
        evidence.put("generatedArtifactCount", generatedArtifacts.size());
        evidence.put("generatedArtifacts", generatedArtifacts.stream().map(this::artifactEvidence).toList());
        evidence.put("contracts", this.contractEvidence(generatedArtifacts));
        evidence.put("inputStepIds", this.inputStepIds(input));

        return LaneStepDoneResult.builder()
                .stepId(input.stepId())
                .summary("Generated API artifacts through orchestrator.")
                .evidence(evidence)
                .build();
    }

    private List<GeneratedApiArtifact> generateAll(final List<ApiArtifactGenerationRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<CompletableFuture<GeneratedApiArtifact>> futures = requests.stream()
                    .map(request -> CompletableFuture.supplyAsync(() -> this.apiArtifactGenerationPort.generate(request), executor))
                    .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator
                            .comparing(GeneratedApiArtifact::scope, Comparator.nullsLast(String::compareTo))
                            .thenComparing(GeneratedApiArtifact::dependency, Comparator.nullsLast(String::compareTo)))
                    .toList();
        }
    }

    private List<ApiArtifactGenerationRequest> requests(final List<ApiArtifactGenerationTarget> targets,
                                                        final String pullRequestUrl,
                                                        final String repository) {
        final Map<String, ApiArtifactGenerationRequest> requests = new LinkedHashMap<>();
        for (final ApiArtifactGenerationTarget target : targets) {
            for (final String artifact : target.generatedArtifacts()) {
                this.putRequest(requests, pullRequestUrl, repository, target, artifact, "api-first");
            }
            for (final String artifact : target.consumerArtifacts()) {
                this.putRequest(requests, pullRequestUrl, repository, target, artifact, "client");
            }
            for (final String artifact : target.frontendPackages()) {
                this.putRequest(requests, pullRequestUrl, repository, target, artifact, "frontend");
            }
        }
        return List.copyOf(requests.values());
    }

    private void putRequest(final Map<String, ApiArtifactGenerationRequest> requests,
                            final String pullRequestUrl,
                            final String repository,
                            final ApiArtifactGenerationTarget target,
                            final String artifact,
                            final String generationType) {
        if (artifact == null || artifact.isBlank()) {
            return;
        }
        requests.putIfAbsent(artifact, new ApiArtifactGenerationRequest(
                pullRequestUrl,
                repository,
                artifact,
                target.scope(),
                target.apiFamily(),
                target.serviceCode(),
                generationType
        ));
    }

    private List<ApiArtifactGenerationTarget> resolveTargets(final ApiArtifactGenerationOrchestratorInput input) {
        final Set<String> requestedServiceKeys = this.requestedServiceKeys(input.tasks());
        final Map<String, ApiArtifactGenerationTarget> targets = new LinkedHashMap<>();
        this.services(input.scopeContext()).stream()
                .filter(service -> this.apiRef(service) != null)
                .filter(service -> requestedServiceKeys.isEmpty() || this.matchesAnyServiceKey(service, requestedServiceKeys))
                .forEach(service -> {
                    final ContractRefContext apiRef = this.apiRef(service);
                    targets.putIfAbsent(this.contractIdentity(apiRef), this.target(service, apiRef));
                });
        return List.copyOf(targets.values());
    }

    private Set<String> requestedServiceKeys(final List<ApiArtifactGenerationTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Set.of();
        }
        final Set<String> keys = new LinkedHashSet<>();
        for (final ApiArtifactGenerationTask task : tasks) {
            this.addIfPresent(keys, task.scope());
            if (task.payload() != null) {
                this.addIfPresent(keys, task.payload().scope());
                if (task.payload().consumers() != null) {
                    task.payload().consumers().forEach(consumer -> this.addIfPresent(keys, consumer));
                }
            }
        }
        return Set.copyOf(keys);
    }

    private List<ServiceScopeContext> services(final ScopeContext scopeContext) {
        if (scopeContext == null) {
            return List.of();
        }
        final List<ServiceScopeContext> services = new ArrayList<>();
        if (scopeContext.getService() != null) {
            services.add(scopeContext.getService());
        }
        if (scopeContext.getRelatedServices() != null) {
            services.addAll(scopeContext.getRelatedServices());
        }
        return services.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean matchesAnyServiceKey(final ServiceScopeContext service, final Set<String> requestedServiceKeys) {
        return requestedServiceKeys.contains(service.getServiceId())
                || requestedServiceKeys.contains(service.getScope())
                || requestedServiceKeys.contains(service.getPath())
                || requestedServiceKeys.contains(service.getLabel());
    }

    private ApiArtifactGenerationTarget target(final ServiceScopeContext service, final ContractRefContext apiRef) {
        return new ApiArtifactGenerationTarget(
                service.getServiceId(),
                service.getScope(),
                apiRef.getSourceRepo(),
                apiRef.getApiFamily(),
                apiRef.getServiceCode(),
                this.list(apiRef.getGeneratedArtifacts()),
                this.list(apiRef.getConsumerArtifacts()),
                this.list(apiRef.getFrontendPackages())
        );
    }

    private ContractRefContext apiRef(final ServiceScopeContext service) {
        if (service == null || service.getContractRefs() == null) {
            return null;
        }
        return service.getContractRefs().get(API_CONTRACT_REF);
    }

    private String contractIdentity(final ContractRefContext apiRef) {
        return String.join("|",
                Objects.toString(apiRef.getSourceRepo(), ""),
                Objects.toString(apiRef.getApiFamily(), ""),
                Objects.toString(apiRef.getServiceCode(), "")
        );
    }

    private Map<String, Object> targetEvidence(final ApiArtifactGenerationTarget target) {
        final Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("serviceId", target.serviceId());
        evidence.put("scope", target.scope());
        evidence.put("sourceRepo", target.sourceRepo());
        evidence.put("apiFamily", target.apiFamily());
        evidence.put("serviceCode", target.serviceCode());
        evidence.put("generatedArtifacts", target.generatedArtifacts());
        evidence.put("consumerArtifacts", target.consumerArtifacts());
        evidence.put("frontendPackages", target.frontendPackages());
        return evidence;
    }

    private Map<String, Object> artifactEvidence(final GeneratedApiArtifact artifact) {
        final Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("generationName", artifact.generationName());
        evidence.put("dependency", artifact.dependency());
        evidence.put("runId", artifact.runId());
        evidence.put("workflowRunUrl", artifact.workflowRunUrl());
        evidence.put("notes", artifact.notes());
        return evidence;
    }

    private List<Map<String, Object>> contractEvidence(final List<GeneratedApiArtifact> artifacts) {
        final Map<String, List<GeneratedApiArtifact>> byScope = new LinkedHashMap<>();
        for (final GeneratedApiArtifact artifact : artifacts) {
            byScope.computeIfAbsent(artifact.scope(), ignored -> new ArrayList<>()).add(artifact);
        }
        final List<Map<String, Object>> contracts = new ArrayList<>();
        for (final Map.Entry<String, List<GeneratedApiArtifact>> entry : byScope.entrySet()) {
            final Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("scope", entry.getKey());
            contract.put("method", "MULTIPLE");
            contract.put("path", "MULTIPLE");
            contract.put("operationId", "generated-api-artifacts");
            contract.put("notes", List.of("Generated by Forge AI orchestrator from API lane PR."));
            contract.put("artifacts", entry.getValue().stream().map(this::artifactEvidence).toList());
            contracts.add(contract);
        }
        return contracts;
    }

    private List<String> inputStepIds(final ApiArtifactGenerationOrchestratorInput input) {
        if (input.stepEvidence() == null || input.stepEvidence().isEmpty()) {
            return List.of();
        }
        return List.copyOf(input.stepEvidence().keySet());
    }

    private List<String> list(final List<String> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private void addIfPresent(final Set<String> values, final String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String requirePullRequestUrl(final ApiArtifactGenerationOrchestratorInput input) {
        final String value = this.findString(input.stepEvidence(), Set.of("prUrl", "pullRequestUrl", "pullRequest"));
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("API artifact generation requires PR URL evidence from previous API lane steps");
        }
        return value;
    }

    private String requireRepository(final ApiArtifactGenerationOrchestratorInput input, final String pullRequestUrl) {
        final String repository = this.repositoryFromPullRequestUrl(pullRequestUrl);
        if (this.isGitHubRepositorySlug(repository)) {
            return repository;
        }
        final String value = this.findString(input.stepEvidence(), Set.of("repo", "repository"));
        if (this.isGitHubRepositorySlug(value)) {
            return value;
        }
        if (repository == null || repository.isBlank()) {
            throw new IllegalStateException("API artifact generation requires repository evidence or a GitHub pull request URL");
        }
        return repository;
    }

    private String repositoryFromPullRequestUrl(final String pullRequestUrl) {
        final String marker = "github.com/";
        final int start = pullRequestUrl == null ? -1 : pullRequestUrl.indexOf(marker);
        if (start < 0) {
            return null;
        }
        final String[] parts = pullRequestUrl.substring(start + marker.length()).split("/");
        if (parts.length < 2) {
            return null;
        }
        return parts[0] + "/" + parts[1];
    }

    private boolean isGitHubRepositorySlug(final String value) {
        return value != null && value.matches("[^/\\s]+/[^/\\s]+");
    }

    @SuppressWarnings("unchecked")
    private String findString(final Object value, final Set<String> keys) {
        if (value instanceof Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                final String key = Objects.toString(entry.getKey(), "");
                if (keys.contains(key) && entry.getValue() != null) {
                    return Objects.toString(entry.getValue(), null);
                }
            }
            for (final Object nested : map.values()) {
                final String found = this.findString(nested, keys);
                if (found != null && !found.isBlank()) {
                    return found;
                }
            }
        }
        if (value instanceof List<?> list) {
            for (final Object nested : list) {
                final String found = this.findString(nested, keys);
                if (found != null && !found.isBlank()) {
                    return found;
                }
            }
        }
        return null;
    }
}
