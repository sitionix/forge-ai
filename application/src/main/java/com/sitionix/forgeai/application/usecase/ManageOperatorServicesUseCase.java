package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceActionResponse;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceContractReference;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDatabase;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDefaultMode;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDetailResponse;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceRuntimeState;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceSummary;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceWorkspaceState;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServicesResponse;
import com.sitionix.forgeai.domain.port.OperatorServiceRuntimePort;
import com.sitionix.forgeai.domain.port.OperatorServiceWorkspacePort;
import com.sitionix.forgeai.domain.props.ContractRefView;
import com.sitionix.forgeai.domain.props.DbConfigView;
import com.sitionix.forgeai.domain.props.DeployConfigView;
import com.sitionix.forgeai.domain.props.DeployUnitConfigView;
import com.sitionix.forgeai.domain.props.ServiceConfigView;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.ManageOperatorServices;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManageOperatorServicesUseCase implements ManageOperatorServices {

    private final ServicePropertiesProvider servicePropertiesProvider;
    private final OperatorServiceWorkspacePort workspacePort;
    private final OperatorServiceRuntimePort runtimePort;

    @Override
    public OperatorServicesResponse services() {
        final Map<String, ServiceConfigView> services = this.servicesById();
        return new OperatorServicesResponse(services.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> this.summary(entry.getKey(), entry.getValue()))
                .sorted(Comparator
                        .comparing(OperatorServiceSummary::group, Comparator.nullsLast(String::compareTo))
                        .thenComparing(OperatorServiceSummary::label, Comparator.nullsLast(String::compareTo))
                        .thenComparing(OperatorServiceSummary::serviceId))
                .toList());
    }

    @Override
    public OperatorServiceDetailResponse service(final String serviceId) {
        final ServiceConfigView service = this.serviceById(serviceId);
        return new OperatorServiceDetailResponse(
                this.summary(serviceId, service),
                this.contractReferences(service),
                this.database(service)
        );
    }

    @Override
    public OperatorServiceActionResponse cloneService(final String serviceId) {
        final ServiceConfigView service = this.serviceById(serviceId);
        final OperatorServiceWorkspaceState workspace = this.workspacePort.cloneRepository(
                serviceId,
                service.getPath(),
                this.repository(service)
        );
        return new OperatorServiceActionResponse(
                serviceId,
                workspace.exists() ? "CLONED" : "FAILED",
                workspace.exists() ? "Repository is available locally." : "Repository was not cloned.",
                this.summary(serviceId, service, workspace)
        );
    }

    @Override
    public OperatorServiceActionResponse defaultService(final String serviceId, final OperatorServiceDefaultMode mode) {
        final ServiceConfigView service = this.serviceById(serviceId);
        final OperatorServiceWorkspaceState workspace = this.workspacePort.resetToDefaultBranch(
                serviceId,
                service.getPath(),
                this.repository(service),
                mode == null ? OperatorServiceDefaultMode.CHECKOUT : mode
        );
        final boolean blocked = workspace.dirty() && workspace.warnings() != null && workspace.warnings().stream()
                .anyMatch(warning -> warning.contains("local changes"));
        return new OperatorServiceActionResponse(
                serviceId,
                blocked ? "BLOCKED" : "DEFAULTED",
                blocked ? "Workspace has local changes. Commit or stash before defaulting." : "Workspace switched to default branch.",
                this.summary(serviceId, service, workspace)
        );
    }

    private Map<String, ServiceConfigView> servicesById() {
        final Map<String, ServiceConfigView> services = this.servicePropertiesProvider.getServices();
        return services == null ? Map.of() : services;
    }

    private ServiceConfigView serviceById(final String serviceId) {
        if (!this.hasText(serviceId)) {
            throw new IllegalArgumentException("Service id is required");
        }
        final ServiceConfigView service = this.servicesById().get(serviceId);
        if (service == null) {
            throw new IllegalArgumentException("Unknown service: " + serviceId);
        }
        return service;
    }

    private OperatorServiceSummary summary(
            final String serviceId,
            final ServiceConfigView service
    ) {
        return this.summary(serviceId, service, this.workspace(serviceId, service));
    }

    private OperatorServiceSummary summary(
            final String serviceId,
            final ServiceConfigView service,
            final OperatorServiceWorkspaceState workspace
    ) {
        final OperatorServiceRuntimeState serviceRuntime = this.serviceRuntime(service.getDeploy());
        final OperatorServiceRuntimeState databaseRuntime = this.databaseRuntime(service);
        final DbConfigView db = service.getDb();
        final boolean dbRequired = db != null && Boolean.TRUE.equals(db.getRequired());
        final List<String> warnings = new ArrayList<>();
        if (workspace.warnings() != null) {
            warnings.addAll(workspace.warnings());
        }
        if (!workspace.exists() && !this.hasText(workspace.cloneUrl())) {
            warnings.add("Service repository is not configured.");
        }
        return new OperatorServiceSummary(
                serviceId,
                Objects.toString(service.getLabel(), serviceId),
                service.getPath(),
                workspace.absolutePath(),
                service.getGroup() == null ? null : service.getGroup().name(),
                this.list(service.getTags()),
                workspace.repository(),
                workspace.cloneUrl(),
                workspace.exists(),
                workspace.gitRepository(),
                workspace.branch(),
                workspace.defaultBranch(),
                workspace.dirty(),
                serviceRuntime.status(),
                serviceRuntime.containerName(),
                !workspace.exists() && this.hasText(workspace.cloneUrl()),
                workspace.gitRepository() && this.hasText(workspace.defaultBranch()),
                dbRequired,
                db == null ? null : db.getType(),
                db == null ? null : db.getKey(),
                databaseRuntime.status(),
                databaseRuntime.containerName(),
                warnings
        );
    }

    private OperatorServiceWorkspaceState workspace(
            final String serviceId,
            final ServiceConfigView service
    ) {
        return this.workspacePort.inspect(serviceId, service.getPath(), this.repository(service));
    }

    private OperatorServiceDatabase database(final ServiceConfigView service) {
        final DbConfigView db = service.getDb();
        if (db == null) {
            return new OperatorServiceDatabase(false, null, null, null, "NOT_CONFIGURED", null, "Database is not configured.");
        }
        final OperatorServiceRuntimeState runtime = this.databaseRuntime(service);
        return new OperatorServiceDatabase(
                Boolean.TRUE.equals(db.getRequired()),
                db.getType(),
                db.getMode(),
                db.getKey(),
                runtime.status(),
                runtime.containerName(),
                runtime.message()
        );
    }

    private List<OperatorServiceContractReference> contractReferences(
            final ServiceConfigView service
    ) {
        final Map<String, ContractRefView> refs = service.getContractRefs();
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        return refs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> this.contractReference(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(OperatorServiceContractReference::refKey))
                .toList();
    }

    private OperatorServiceContractReference contractReference(
            final String refKey,
            final ContractRefView ref
    ) {
        final OperatorServiceWorkspaceState source = this.hasText(ref.getSourceRepo())
                ? this.workspacePort.inspect(ref.getSourceRepo(), ref.getSourceRepo(), null)
                : null;
        return new OperatorServiceContractReference(
                refKey,
                ref.getSourceRepo(),
                source == null ? null : source.absolutePath(),
                source != null && source.exists(),
                ref.getApiFamily(),
                ref.getEventFamily(),
                ref.getServiceCode(),
                ref.getRoot(),
                this.list(ref.getSchemas()),
                this.list(ref.getOperations()),
                this.list(ref.getTopics()),
                this.list(ref.getPayloads()),
                this.list(ref.getGeneratedArtifacts()),
                this.list(ref.getConsumerArtifacts()),
                this.list(ref.getFrontendPackages())
        );
    }

    private String repository(final ServiceConfigView service) {
        if (this.hasText(service.getRepo())) {
            return service.getRepo();
        }
        final DeployConfigView deploy = service.getDeploy();
        return deploy == null ? null : deploy.getRepo();
    }

    private String databaseRuntimeName(final ServiceConfigView service) {
        final String deployDbName = this.deployUnitName(service.getDeploy(), false);
        if (this.hasText(deployDbName)) {
            return deployDbName;
        }
        final DbConfigView db = service.getDb();
        return db == null ? null : db.getKey();
    }

    private String deployUnitName(final DeployConfigView deploy, final boolean serviceUnit) {
        if (deploy == null) {
            return null;
        }
        final DeployUnitConfigView unit = serviceUnit ? deploy.getService() : deploy.getDb();
        return unit == null ? null : unit.getName();
    }

    private OperatorServiceRuntimeState serviceRuntime(final DeployConfigView deploy) {
        final DeployUnitConfigView unit = deploy == null ? null : deploy.getService();
        return this.runtimePort.healthcheck(unit == null ? null : unit.getHealthcheckUrl());
    }

    private OperatorServiceRuntimeState databaseRuntime(final ServiceConfigView service) {
        return this.runtimePort.container(this.databaseRuntimeName(service));
    }

    private List<String> list(final List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
