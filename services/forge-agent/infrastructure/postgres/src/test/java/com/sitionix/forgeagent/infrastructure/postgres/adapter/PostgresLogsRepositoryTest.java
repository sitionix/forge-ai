package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.infrastructure.postgres.entity.*;
import com.sitionix.forgeagent.infrastructure.postgres.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PostgresLogsRepositoryTest {
  @Test
  void persistsNullableServiceAndEveryProviderField() {
    var spring = mock(SpringDataLogSourceRepository.class);
    when(spring.save(any())).thenAnswer(i -> i.getArgument(0));
    var repository = new PostgresLogSourceRepository(spring);
    UUID p = UUID.randomUUID(), ssh = UUID.randomUUID();
    var now = Instant.EPOCH;
    var docker =
        new LogSource(
            UUID.randomUUID(),
            p,
            "compose",
            null,
            LogConnectionType.SSH,
            ssh,
            LogProviderType.DOCKER,
            new DockerLogConfiguration(null, "web", "/repo/compose.yaml"),
            true,
            now,
            now);
    repository.save(docker);
    var captor = ArgumentCaptor.forClass(LogSourceEntity.class);
    verify(spring).save(captor.capture());
    assertThat(captor.getValue().getServiceId()).isNull();
    assertThat(captor.getValue().getProjectId()).isEqualTo(p);
    assertThat(captor.getValue().getSshConnectionId()).isEqualTo(ssh);
    assertThat(captor.getValue().getComposeService()).isEqualTo("web");
    assertThat(captor.getValue().getComposeFile()).isEqualTo("/repo/compose.yaml");
  }

  @Test
  void listsMultipleSourcesForOneProjectWithoutServiceUniqueness() {
    var spring = mock(SpringDataLogSourceRepository.class);
    UUID p = UUID.randomUUID();
    var one = entity(UUID.randomUUID(), p, "one");
    var two = entity(UUID.randomUUID(), p, "two");
    when(spring.findAllByProjectIdOrderByNameAscIdAsc(p)).thenReturn(List.of(one, two));
    assertThat(new PostgresLogSourceRepository(spring).findByProjectId(p))
        .extracting(LogSource::name)
        .containsExactly("one", "two");
  }

  @Test
  void persistsReusableSshProfileSecretOnlyInPersistenceEntity() {
    var spring = mock(SpringDataSshConnectionRepository.class);
    when(spring.save(any())).thenAnswer(i -> i.getArgument(0));
    var repo = new PostgresSshConnectionRepository(spring);
    var profile =
        new SshConnection(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "rover",
            "rover.local",
            22,
            "op",
            "/keys/id",
            Instant.EPOCH,
            Instant.EPOCH);
    assertThat(repo.save(profile).privateKeyPath()).isEqualTo("/keys/id");
    verify(spring)
        .save(
            argThat(
                e ->
                    e.getPrivateKeyPath().equals("/keys/id")
                        && e.getProjectId().equals(profile.projectId())));
  }

  private LogSourceEntity entity(UUID id, UUID project, String name) {
    var e = new LogSourceEntity();
    e.setId(id);
    e.setProjectId(project);
    e.setName(name);
    e.setConnectionType(LogConnectionType.LOCAL);
    e.setProvider(LogProviderType.DOCKER);
    e.setDockerContainer(name);
    e.setEnabled(true);
    e.setCreatedAt(Instant.EPOCH);
    e.setUpdatedAt(Instant.EPOCH);
    return e;
  }
}
