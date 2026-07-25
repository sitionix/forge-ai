package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDefaultMode;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceRuntimeState;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceWorkspaceState;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.port.OperatorServiceRuntimePort;
import com.sitionix.forgeai.domain.port.OperatorServiceWorkspacePort;
import com.sitionix.forgeai.domain.props.ContractRefView;
import com.sitionix.forgeai.domain.props.DbConfigView;
import com.sitionix.forgeai.domain.props.DeployConfigView;
import com.sitionix.forgeai.domain.props.DeployUnitConfigView;
import com.sitionix.forgeai.domain.props.ServiceConfigView;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.ManageOperatorServices;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageOperatorServicesUseCaseTest {

    private ManageOperatorServices useCase;

    @Mock
    private ServicePropertiesProvider servicePropertiesProvider;
    @Mock
    private OperatorServiceWorkspacePort workspacePort;
    @Mock
    private OperatorServiceRuntimePort runtimePort;

    @BeforeEach
    void setUp() {
        this.useCase = new ManageOperatorServicesUseCase(
                this.servicePropertiesProvider,
                this.workspacePort,
                this.runtimePort
        );
    }

    @Test
    void givenConfiguredServices_whenServices_thenAggregateYamlWorkspaceRuntimeAndDatabaseState() {
        final ServiceConfigView service = this.service(true, false);
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("atmssox", service));
        when(this.workspacePort.inspect("atmssox", "automationservice-sox", "Sitionix/automationservice-sox"))
                .thenReturn(this.workspace("automationservice-sox", true));
        when(this.runtimePort.healthcheck("http://127.0.0.1:9083/atmssox/actuator/health"))
                .thenReturn(new OperatorServiceRuntimeState("UP", "http://127.0.0.1:9083/atmssox/actuator/health", "HTTP 200"));
        when(this.runtimePort.container("atms_sox"))
                .thenReturn(new OperatorServiceRuntimeState("DOWN", "atms_sox", "Exited"));

        final var actual = this.useCase.services();

        assertThat(actual.services()).singleElement().satisfies(summary -> {
            assertThat(summary.serviceId()).isEqualTo("atmssox");
            assertThat(summary.label()).isEqualTo("Automation Service SOX");
            assertThat(summary.repository()).isEqualTo("Sitionix/automationservice-sox");
            assertThat(summary.absolutePath()).isEqualTo("/workspace/automationservice-sox");
            assertThat(summary.exists()).isTrue();
            assertThat(summary.gitRepository()).isTrue();
            assertThat(summary.branch()).isEqualTo("feature/SITIONIX-28");
            assertThat(summary.defaultBranch()).isEqualTo("develop");
            assertThat(summary.serviceRuntimeStatus()).isEqualTo("UP");
            assertThat(summary.dbRequired()).isTrue();
            assertThat(summary.dbRuntimeStatus()).isEqualTo("DOWN");
            assertThat(summary.cloneAvailable()).isFalse();
            assertThat(summary.defaultAvailable()).isTrue();
        });
    }

    @Test
    void givenToolServiceWithTopLevelRepository_whenServices_thenUseRepositoryWithoutDeployConfig() {
        final ServiceConfigView service = mock(ServiceConfigView.class);
        when(service.getLabel()).thenReturn("App AFESOX Contracts");
        when(service.getPath()).thenReturn("app-afesox");
        when(service.getRepo()).thenReturn("Sitionix/app-afesox");
        when(service.getGroup()).thenReturn(ServiceGroup.TOOL);
        when(service.getTags()).thenReturn(List.of("api-first", "contracts"));
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("app-afesox", service));
        when(this.workspacePort.inspect("app-afesox", "app-afesox", "Sitionix/app-afesox"))
                .thenReturn(this.workspace("app-afesox", false));
        when(this.runtimePort.healthcheck(null))
                .thenReturn(new OperatorServiceRuntimeState("DOWN", null, "No healthcheck configured"));
        when(this.runtimePort.container(null))
                .thenReturn(new OperatorServiceRuntimeState("DOWN", null, "No container configured"));

        final var actual = this.useCase.services();

        assertThat(actual.services()).singleElement().satisfies(summary -> {
            assertThat(summary.serviceId()).isEqualTo("app-afesox");
            assertThat(summary.group()).isEqualTo("TOOL");
            assertThat(summary.repository()).isEqualTo("Sitionix/app-afesox");
            assertThat(summary.cloneAvailable()).isTrue();
        });
        verify(this.workspacePort).inspect("app-afesox", "app-afesox", "Sitionix/app-afesox");
    }

    @Test
    void givenServiceDetailRequest_whenService_thenReturnContractRefsAndDatabase() {
        final ServiceConfigView service = this.service(true, true);
        final ContractRefView contractRef = this.contractRef();
        when(service.getContractRefs()).thenReturn(Map.of("api", contractRef));
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("atmssox", service));
        when(this.workspacePort.inspect("atmssox", "automationservice-sox", "Sitionix/automationservice-sox"))
                .thenReturn(this.workspace("automationservice-sox", true));
        when(this.workspacePort.inspect("app-afesox", "app-afesox", null))
                .thenReturn(this.workspace("app-afesox", true));
        when(this.runtimePort.healthcheck("http://127.0.0.1:9083/atmssox/actuator/health"))
                .thenReturn(new OperatorServiceRuntimeState("UP", "http://127.0.0.1:9083/atmssox/actuator/health", "HTTP 200"));
        when(this.runtimePort.container("atms_sox"))
                .thenReturn(new OperatorServiceRuntimeState("UP", "atms_sox", "Up"));

        final var actual = this.useCase.service("atmssox");

        assertThat(actual.service().serviceId()).isEqualTo("atmssox");
        assertThat(actual.database().required()).isTrue();
        assertThat(actual.database().key()).isEqualTo("atms_sox");
        assertThat(actual.contractReferences()).singleElement().satisfies(ref -> {
            assertThat(ref.refKey()).isEqualTo("api");
            assertThat(ref.sourceRepo()).isEqualTo("app-afesox");
            assertThat(ref.sourceExists()).isTrue();
            assertThat(ref.apiFamily()).isEqualTo("atmssox");
            assertThat(ref.schemas()).containsExactly("app-afesox/apis/atmssox/rest/schemas");
            assertThat(ref.generatedArtifacts()).containsExactly("app-afesox-atmssox-api-first-stable");
        });
    }

    @Test
    void givenCloneRequest_whenCloneService_thenDelegateToWorkspacePortWithConfiguredRepository() {
        final ServiceConfigView service = this.service(true, false);
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("atmssox", service));
        when(this.workspacePort.cloneRepository("atmssox", "automationservice-sox", "Sitionix/automationservice-sox"))
                .thenReturn(this.workspace("automationservice-sox", true));
        when(this.runtimePort.healthcheck("http://127.0.0.1:9083/atmssox/actuator/health"))
                .thenReturn(new OperatorServiceRuntimeState("UP", "http://127.0.0.1:9083/atmssox/actuator/health", "HTTP 200"));
        when(this.runtimePort.container("atms_sox"))
                .thenReturn(new OperatorServiceRuntimeState("UP", "atms_sox", "Up"));

        final var actual = this.useCase.cloneService("atmssox");

        verify(this.workspacePort).cloneRepository("atmssox", "automationservice-sox", "Sitionix/automationservice-sox");
        assertThat(actual.status()).isEqualTo("CLONED");
        assertThat(actual.service().serviceId()).isEqualTo("atmssox");
    }

    @Test
    void givenDefaultRequest_whenDefaultService_thenDelegateToWorkspacePortWithConfiguredPath() {
        final ServiceConfigView service = this.service(true, false);
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of("atmssox", service));
        when(this.workspacePort.resetToDefaultBranch(
                "atmssox",
                "automationservice-sox",
                "Sitionix/automationservice-sox",
                OperatorServiceDefaultMode.STASH
        ))
                .thenReturn(this.workspace("automationservice-sox", true));
        when(this.runtimePort.healthcheck("http://127.0.0.1:9083/atmssox/actuator/health"))
                .thenReturn(new OperatorServiceRuntimeState("UP", "http://127.0.0.1:9083/atmssox/actuator/health", "HTTP 200"));
        when(this.runtimePort.container("atms_sox"))
                .thenReturn(new OperatorServiceRuntimeState("UP", "atms_sox", "Up"));

        final var actual = this.useCase.defaultService("atmssox", OperatorServiceDefaultMode.STASH);

        verify(this.workspacePort)
                .resetToDefaultBranch(
                        "atmssox",
                        "automationservice-sox",
                        "Sitionix/automationservice-sox",
                        OperatorServiceDefaultMode.STASH
                );
        assertThat(actual.status()).isEqualTo("DEFAULTED");
        assertThat(actual.service().branch()).isEqualTo("feature/SITIONIX-28");
    }

    @Test
    void givenUnknownService_whenService_thenReject() {
        when(this.servicePropertiesProvider.getServices()).thenReturn(Map.of());

        assertThatThrownBy(() -> this.useCase.service("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown service: missing");
    }

    private ServiceConfigView service(final boolean includeRepository, final boolean includeDbMode) {
        final ServiceConfigView service = mock(ServiceConfigView.class);
        final DeployConfigView deploy = this.deploy(includeRepository);
        final DbConfigView db = this.db(includeDbMode);
        when(service.getLabel()).thenReturn("Automation Service SOX");
        when(service.getPath()).thenReturn("automationservice-sox");
        when(service.getGroup()).thenReturn(ServiceGroup.BACKEND);
        when(service.getTags()).thenReturn(List.of("java", "postgresql"));
        when(service.getDeploy()).thenReturn(deploy);
        when(service.getDb()).thenReturn(db);
        return service;
    }

    private DeployConfigView deploy(final boolean includeRepository) {
        final DeployConfigView deploy = mock(DeployConfigView.class);
        final DeployUnitConfigView serviceUnit = this.serviceUnit();
        final DeployUnitConfigView dbUnit = this.dbUnit("atms_sox");
        if (includeRepository) {
            when(deploy.getRepo()).thenReturn("Sitionix/automationservice-sox");
        }
        when(deploy.getService()).thenReturn(serviceUnit);
        when(deploy.getDb()).thenReturn(dbUnit);
        return deploy;
    }

    private DeployUnitConfigView serviceUnit() {
        final DeployUnitConfigView unit = mock(DeployUnitConfigView.class);
        when(unit.getHealthcheckUrl()).thenReturn("http://127.0.0.1:9083/atmssox/actuator/health");
        return unit;
    }

    private DeployUnitConfigView dbUnit(final String name) {
        final DeployUnitConfigView unit = mock(DeployUnitConfigView.class);
        when(unit.getName()).thenReturn(name);
        return unit;
    }

    private DbConfigView db(final boolean includeMode) {
        final DbConfigView db = mock(DbConfigView.class);
        when(db.getRequired()).thenReturn(true);
        when(db.getType()).thenReturn("postgresql");
        if (includeMode) {
            when(db.getMode()).thenReturn("service_and_db");
        }
        when(db.getKey()).thenReturn("atms_sox");
        return db;
    }

    private ContractRefView contractRef() {
        final ContractRefView ref = mock(ContractRefView.class);
        when(ref.getSourceRepo()).thenReturn("app-afesox");
        when(ref.getApiFamily()).thenReturn("atmssox");
        when(ref.getServiceCode()).thenReturn("atmssox");
        when(ref.getRoot()).thenReturn("app-afesox/apis/atmssox/rest/openapi.yml");
        when(ref.getSchemas()).thenReturn(List.of("app-afesox/apis/atmssox/rest/schemas"));
        when(ref.getGeneratedArtifacts()).thenReturn(List.of("app-afesox-atmssox-api-first-stable"));
        return ref;
    }

    private OperatorServiceWorkspaceState workspace(final String path, final boolean exists) {
        return new OperatorServiceWorkspaceState(
                path,
                "/workspace/" + path,
                "Sitionix/" + path,
                "git@github.com:Sitionix/" + path + ".git",
                exists,
                exists,
                exists ? "feature/SITIONIX-28" : null,
                exists ? "develop" : null,
                false,
                List.of()
        );
    }
}
