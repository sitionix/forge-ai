package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.LOG_SOURCE;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT_SERVICE;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.SSH_CONNECTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.infrastructure.postgres.entity.LogSourceEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.SshConnectionEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentProjectLogsSchemaIT {

  private static final UUID PROJECT_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
  private static final UUID SERVICE_ID = UUID.fromString("90000000-0000-4000-8000-000000000010");

  @Autowired private ForgeAgentTestManager forgeIt;

  @Test
  void logSourceProjectForeignKeyIsEnforced() {
    assertThatThrownBy(
            () ->
                this.forgeIt
                    .postgresql()
                    .create()
                    .to(LOG_SOURCE.withJson("logs_source_missing_project.json"))
                    .build())
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void projectDeletionCascadesLogSourcesAndSshConnections() {
    this.forgeIt
        .postgresql()
        .create()
        .to(PROJECT.withJson("logs_project.json"))
        .to(SSH_CONNECTION.withJson("logs_ssh.json"))
        .to(LOG_SOURCE.withJson("logs_source_ssh_docker.json"))
        .build();

    this.forgeIt
        .mockMvc()
        .ping(DELETE_PROJECT)
        .withPathParameters(PathParams.create().add("projectId", PROJECT_ID))
        .expectStatus(HttpStatus.NO_CONTENT)
        .assertAndCreate();

    assertThat(this.forgeIt.postgresql().get(LogSourceEntity.class).getAll()).isEmpty();
    assertThat(this.forgeIt.postgresql().get(SshConnectionEntity.class).getAll()).isEmpty();
  }

  @Test
  void sshConnectionProjectForeignKeyIsEnforced() {
    assertThatThrownBy(
            () ->
                this.forgeIt
                    .postgresql()
                    .create()
                    .to(SSH_CONNECTION.withJson("logs_ssh_missing_project.json"))
                    .build())
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void referencedSshConnectionDeletionIsRestricted() {
    this.forgeIt
        .postgresql()
        .create()
        .to(PROJECT.withJson("logs_project.json"))
        .to(SSH_CONNECTION.withJson("logs_ssh.json"))
        .to(LOG_SOURCE.withJson("logs_source_ssh_docker.json"))
        .build();

    assertThatThrownBy(() -> this.forgeIt.postgresql().clearAllData(List.of(SSH_CONNECTION)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void nullableAndRepeatedServiceAssociationsArePersisted() {
    this.forgeIt
        .postgresql()
        .create()
        .to(PROJECT.withJson("logs_project.json"))
        .to(PROJECT_SERVICE.withJson("logs_service.json"))
        .to(LOG_SOURCE.withJson("logs_source_custom.json"))
        .to(LOG_SOURCE.withJson("logs_source_service_app.json"))
        .to(LOG_SOURCE.withJson("logs_source_service_worker.json"))
        .build();

    final List<LogSourceEntity> sources =
        this.forgeIt.postgresql().get(LogSourceEntity.class).getAll();
    assertThat(sources).hasSize(3);
    assertThat(sources).filteredOn(source -> source.getServiceId() == null).hasSize(1);
    assertThat(sources).filteredOn(source -> SERVICE_ID.equals(source.getServiceId())).hasSize(2);

    this.forgeIt.postgresql().clearAllData(List.of(PROJECT_SERVICE));

    final List<LogSourceEntity> retained =
        this.forgeIt.postgresql().get(LogSourceEntity.class).getAll();
    assertThat(retained).hasSize(3);
    assertThat(retained).allMatch(source -> source.getServiceId() == null);
  }

  @Test
  void localTransportRejectsSshConnection() {
    assertInvalidWithSsh("logs_source_invalid_local_with_ssh.json");
  }

  @Test
  void sshTransportRequiresSshConnection() {
    assertInvalidWithoutSsh("logs_source_invalid_ssh_without_profile.json");
  }

  @Test
  void systemdProviderRequiresSshTransport() {
    assertInvalidWithoutSsh("logs_source_invalid_local_systemd.json");
  }

  @Test
  void fileProviderRequiresSshTransport() {
    assertInvalidWithoutSsh("logs_source_invalid_local_file.json");
  }

  @Test
  void dockerProviderRequiresContainerOrComposeService() {
    assertInvalidWithoutSsh("logs_source_invalid_docker_empty.json");
  }

  @Test
  void systemdUnitAndFullJournalModesSatisfyProviderConstraint() {
    this.forgeIt
        .postgresql()
        .create()
        .to(PROJECT.withJson("logs_project.json"))
        .to(SSH_CONNECTION.withJson("logs_ssh.json"))
        .to(LOG_SOURCE.withJson("logs_source_systemd_unit.json"))
        .to(LOG_SOURCE.withJson("logs_source_systemd_full_journal.json"))
        .build();

    assertThat(this.forgeIt.postgresql().get(LogSourceEntity.class).getAll()).hasSize(2);
  }

  @Test
  void systemdTargetModeConstraintRejectsMismatchedUnit() {
    assertInvalidWithSsh("logs_source_invalid_systemd_unit_missing.json");
    assertInvalidWithSsh("logs_source_invalid_full_journal_unit.json");
  }

  @Test
  void privateKeyAndPasswordProfilesSatisfyAuthenticationConstraint() {
    this.forgeIt
        .postgresql()
        .create()
        .to(PROJECT.withJson("logs_project.json"))
        .to(SSH_CONNECTION.withJson("logs_ssh.json"))
        .to(SSH_CONNECTION.withJson("logs_ssh_password.json"))
        .build();

    assertThat(this.forgeIt.postgresql().get(SshConnectionEntity.class).getAll()).hasSize(2);
  }

  @Test
  void privateKeyAuthenticationRequiresPrivateKeyPath() {
    assertInvalidSshProfile("logs_ssh_invalid_key_without_path.json");
  }

  @Test
  void privateKeyAuthenticationRejectsPassword() {
    assertInvalidSshProfile("logs_ssh_invalid_key_with_password.json");
  }

  @Test
  void passwordAuthenticationRequiresPassword() {
    assertInvalidSshProfile("logs_ssh_invalid_password_without_secret.json");
  }

  @Test
  void passwordAuthenticationRejectsPrivateKeyPath() {
    assertInvalidSshProfile("logs_ssh_invalid_password_with_key.json");
  }

  private void assertInvalidWithSsh(final String fixture) {
    assertThatThrownBy(
            () ->
                this.forgeIt
                    .postgresql()
                    .create()
                    .to(PROJECT.withJson("logs_project.json"))
                    .to(SSH_CONNECTION.withJson("logs_ssh.json"))
                    .to(LOG_SOURCE.withJson(fixture))
                    .build())
        .isInstanceOf(RuntimeException.class);
  }

  private void assertInvalidWithoutSsh(final String fixture) {
    assertThatThrownBy(
            () ->
                this.forgeIt
                    .postgresql()
                    .create()
                    .to(PROJECT.withJson("logs_project.json"))
                    .to(LOG_SOURCE.withJson(fixture))
                    .build())
        .isInstanceOf(RuntimeException.class);
  }

  private void assertInvalidSshProfile(final String fixture) {
    assertThatThrownBy(
            () ->
                this.forgeIt
                    .postgresql()
                    .create()
                    .to(PROJECT.withJson("logs_project.json"))
                    .to(SSH_CONNECTION.withJson(fixture))
                    .build())
        .isInstanceOf(RuntimeException.class);
  }
}
