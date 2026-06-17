package com.sitionix.forgeai.application.laneexecution;

import com.sitionix.forgeai.domain.model.codex.CodexLaneWorkspace;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.AgentConfigView;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.props.ContractRefView;
import com.sitionix.forgeai.domain.props.ServiceConfigView;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.ResolveCodexLaneWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServiceConfigCodexLaneWorkspaceResolver implements ResolveCodexLaneWorkspace {

    private static final String GLOBAL_SERVICE_ID = "global";

    private final AgentPropertiesProvider agentPropertiesProvider;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final TicketRepository ticketRepository;

    @Override
    public CodexLaneWorkspace resolve(final ReadyToStartLane lane) {
        final Path forgeAiRoot = this.forgeAiRoot();
        final Set<String> serviceIds = this.serviceIdsForLane(lane);
        final LinkedHashSet<Path> serviceRoots = this.serviceRoots(serviceIds, forgeAiRoot);
        final LinkedHashSet<Path> contractRoots = this.contractRoots(lane, serviceIds, forgeAiRoot);
        final Path cwd = this.preferredCwd(lane, serviceRoots, contractRoots)
                .orElse(forgeAiRoot);

        final LinkedHashSet<Path> runtimeRoots = new LinkedHashSet<>();
        runtimeRoots.add(cwd);
        runtimeRoots.addAll(serviceRoots);
        runtimeRoots.addAll(contractRoots);

        return new CodexLaneWorkspace(
                cwd.toString(),
                runtimeRoots.stream().map(Path::toString).toList()
        );
    }

    private Optional<Path> preferredCwd(final ReadyToStartLane lane,
                                        final LinkedHashSet<Path> serviceRoots,
                                        final LinkedHashSet<Path> contractRoots) {
        if (this.isGlobalContractLane(lane)) {
            return contractRoots.stream().findFirst().or(() -> serviceRoots.stream().findFirst());
        }
        return serviceRoots.stream().findFirst().or(() -> contractRoots.stream().findFirst());
    }

    private Path forgeAiRoot() {
        Path candidate = this.currentWorkingDirectory();
        while (candidate != null) {
            if (this.isForgeAiRoot(candidate)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return this.currentWorkingDirectory();
    }

    private boolean isForgeAiRoot(final Path candidate) {
        return Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isRegularFile(candidate.resolve("boot/src/main/resources/services.yaml"));
    }

    private Path currentWorkingDirectory() {
        final String userDir = System.getProperty("user.dir");
        return this.hasText(userDir)
                ? Path.of(userDir).toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize();
    }

    private LinkedHashSet<Path> serviceRoots(final Set<String> serviceIds, final Path forgeAiRoot) {
        final LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (final String serviceId : serviceIds) {
            this.serviceRoot(serviceId, forgeAiRoot).ifPresent(roots::add);
        }
        return roots;
    }

    private Set<String> serviceIdsForLane(final ReadyToStartLane lane) {
        if (lane == null) {
            return Collections.emptySet();
        }
        if (!Objects.equals(ScopeMode.GLOBAL_SCOPE, lane.getScope())) {
            return this.hasText(lane.getServiceId()) && !Objects.equals(GLOBAL_SERVICE_ID, lane.getServiceId())
                    ? Set.of(lane.getServiceId())
                    : Collections.emptySet();
        }
        final Ticket ticket = this.ticketRepository.findById(lane.getTicketId()).orElse(null);
        if (ticket == null || ticket.getLanes() == null) {
            return Collections.emptySet();
        }
        final LinkedHashSet<String> serviceIds = new LinkedHashSet<>();
        ticket.getLanes().stream()
                .map(Lane::getServiceId)
                .filter(this::hasText)
                .filter(serviceId -> !Objects.equals(GLOBAL_SERVICE_ID, serviceId))
                .forEach(serviceIds::add);
        return serviceIds;
    }

    private Optional<Path> serviceRoot(final String serviceId, final Path forgeAiRoot) {
        final ServiceConfigView service = this.services().get(serviceId);
        if (service == null || !this.hasText(service.getPath())) {
            return Optional.empty();
        }
        return Optional.of(this.resolveConfiguredPath(serviceId, service.getPath(), forgeAiRoot));
    }

    private LinkedHashSet<Path> contractRoots(final ReadyToStartLane lane, final Set<String> serviceIds, final Path forgeAiRoot) {
        final LinkedHashSet<Path> roots = new LinkedHashSet<>();
        final Optional<String> contractRefKey = this.contractRefKey(lane);
        if (lane == null || !Objects.equals(ScopeMode.GLOBAL_SCOPE, lane.getScope()) || contractRefKey.isEmpty()) {
            return roots;
        }
        for (final String serviceId : serviceIds) {
            final ServiceConfigView service = this.services().get(serviceId);
            if (service == null || service.getContractRefs() == null) {
                continue;
            }
            this.contractRefsForLane(contractRefKey.get(), service).stream()
                    .map(this::contractRootPath)
                    .filter(this::hasText)
                    .map(path -> this.resolveConfiguredPath(serviceId, path, forgeAiRoot))
                    .forEach(roots::add);
        }
        return roots;
    }

    private String contractRootPath(final ContractRefView contractRef) {
        if (contractRef == null || !this.hasText(contractRef.getSourceRepo())) {
            return null;
        }
        final ServiceConfigView configuredContractRepository = this.services().get(contractRef.getSourceRepo());
        if (configuredContractRepository != null && this.hasText(configuredContractRepository.getPath())) {
            return configuredContractRepository.getPath();
        }
        return contractRef.getSourceRepo();
    }

    private List<ContractRefView> contractRefsForLane(
            final String refKey,
            final ServiceConfigView service
    ) {
        if (service.getContractRefs() == null || service.getContractRefs().isEmpty()) {
            return List.of();
        }
        if (this.hasText(refKey)) {
            final ContractRefView ref = service.getContractRefs().get(refKey);
            return ref == null ? List.of() : List.of(ref);
        }
        return List.of();
    }

    private Optional<String> contractRefKey(final ReadyToStartLane lane) {
        if (lane == null || lane.getAgent() == null) {
            return Optional.empty();
        }
        return this.agentConfig(lane.getAgent())
                .flatMap(AgentConfigView::getWorkspaceContractRef)
                .filter(this::hasText);
    }

    private boolean isGlobalContractLane(final ReadyToStartLane lane) {
        return lane != null
                && Objects.equals(ScopeMode.GLOBAL_SCOPE, lane.getScope())
                && this.contractRefKey(lane).isPresent();
    }

    private Optional<AgentConfigView> agentConfig(final Agent agent) {
        if (agent == null || this.agentPropertiesProvider.getAgents() == null) {
            return Optional.empty();
        }
        return this.agentPropertiesProvider.getAgents().stream()
                .filter(config -> config != null && Objects.equals(config.getId(), agent.getId()))
                .findFirst();
    }

    private Path resolveConfiguredPath(final String owner, final String configuredPath, final Path forgeAiRoot) {
        final Path rawPath = Path.of(configuredPath);
        if (rawPath.isAbsolute()) {
            return this.requireDirectory(owner, rawPath.normalize());
        }
        if (forgeAiRoot.getFileName() != null && Objects.equals(forgeAiRoot.getFileName().toString(), configuredPath)) {
            return this.requireDirectory(owner, forgeAiRoot);
        }
        if (forgeAiRoot.getParent() != null) {
            final Path sibling = forgeAiRoot.getParent().resolve(configuredPath).normalize();
            return this.requireDirectory(owner, sibling);
        }
        return this.requireDirectory(owner, rawPath.toAbsolutePath().normalize());
    }

    private Path requireDirectory(final String owner, final Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Configured Codex workspace path does not exist: owner=" + owner + ", path=" + path);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Configured Codex workspace path is not a directory: owner=" + owner + ", path=" + path);
        }
        return path;
    }

    private Map<String, ServiceConfigView> services() {
        final Map<String, ServiceConfigView> services = this.servicePropertiesProvider.getServices();
        return services == null ? Collections.emptyMap() : services;
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
