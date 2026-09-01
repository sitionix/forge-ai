package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectAssetUseCasesTest {
  @Test void validatesSameProjectAndDiscoversCapabilitiesBeforeSaving() {
    var projects=mock(ProjectRepository.class); var assets=mock(ProjectAssetRepository.class);
    var connections=mock(SshConnectionRepository.class); var logs=mock(LogSourceRepository.class);
    var inspection=mock(AssetInspectionPort.class); var discovery=mock(RuntimeTargetDiscoveryPort.class);
    UUID project=UUID.randomUUID(), sshId=UUID.randomUUID(); Instant now=Instant.parse("2026-08-28T00:00:00Z");
    when(projects.findById(project)).thenReturn(Optional.of(new Project(project,"p","p",now,now)));
    var ssh=new SshConnection(sshId,project,"host","example","22".length()+20,"user","/key",now,now);
    when(connections.findById(sshId)).thenReturn(Optional.of(ssh));
    when(inspection.capabilities(ssh)).thenReturn(new AssetCapabilities(true,false));
    when(assets.save(any())).thenAnswer(call->call.getArgument(0));
    var useCases=new ProjectAssetUseCases(projects,assets,connections,logs,inspection,discovery,Clock.fixed(now,ZoneOffset.UTC));
    var saved=useCases.create(project,new CreateProjectAssetCommand("server",sshId));
    assertThat(saved.name()).isEqualTo("server"); verify(inspection).capabilities(ssh); verify(assets).save(any());
  }

  @Test void deletingAssetDeletesOwnedMonitoringFirst() {
    var projects=mock(ProjectRepository.class); var assets=mock(ProjectAssetRepository.class);
    var connections=mock(SshConnectionRepository.class); var logs=mock(LogSourceRepository.class);
    UUID project=UUID.randomUUID(), assetId=UUID.randomUUID(), sshId=UUID.randomUUID(); Instant now=Instant.EPOCH;
    when(projects.findById(project)).thenReturn(Optional.of(new Project(project,"p","p",now,now)));
    var asset=new ProjectAsset(assetId,project,"server",sshId,now,now); when(assets.findById(assetId)).thenReturn(Optional.of(asset));
    var source=new LogSource(UUID.randomUUID(),project,"docker",null,assetId,LogConnectionType.SSH,null,LogProviderType.DOCKER,new DockerLogConfiguration("api",null,null),true,now,now);
    when(logs.findByProjectIdAndAssetId(project,assetId)).thenReturn(List.of(source));
    var useCases=new ProjectAssetUseCases(projects,assets,connections,logs,mock(AssetInspectionPort.class),mock(RuntimeTargetDiscoveryPort.class),Clock.systemUTC());
    useCases.delete(project,assetId); var order=inOrder(logs,assets); order.verify(logs).delete(source); order.verify(assets).delete(asset);
  }

  @Test void desiredStatePreservesUnavailableTargetIdentityAndReplacesOnlyAssetOwnedSources() {
    var projects=mock(ProjectRepository.class); var assets=mock(ProjectAssetRepository.class);
    var connections=mock(SshConnectionRepository.class); var logs=mock(LogSourceRepository.class);
    var discovery=mock(RuntimeTargetDiscoveryPort.class);
    UUID project=UUID.randomUUID(), assetId=UUID.randomUUID(), sshId=UUID.randomUUID(); Instant now=Instant.EPOCH;
    when(projects.findById(project)).thenReturn(Optional.of(new Project(project,"p","p",now,now)));
    var asset=new ProjectAsset(assetId,project,"server",sshId,now,now); when(assets.findById(assetId)).thenReturn(Optional.of(asset));
    var ssh=new SshConnection(sshId,project,"host","example",22,"user","/key",now,now); when(connections.findById(sshId)).thenReturn(Optional.of(ssh));
    var unavailable=new LogSource(UUID.randomUUID(),project,"old.service",LogSourceOwnerType.ASSET,null,assetId,
        LogConnectionType.SSH,null,LogProviderType.SYSTEMD,new SystemdLogConfiguration(SystemdTargetMode.UNIT,"old.service"),true,now,now);
    var removedFile=new LogSource(UUID.randomUUID(),project,"/var/log/old.log",LogSourceOwnerType.ASSET,null,assetId,
        LogConnectionType.SSH,null,LogProviderType.FILE,new FileLogConfiguration("/var/log/old.log"),true,now,now);
    when(logs.findByProjectIdAndAssetId(project,assetId)).thenReturn(List.of(unavailable,removedFile));
    when(discovery.discover(ssh,ServiceRuntimeProvider.DOCKER)).thenReturn(List.of(new RuntimeTargetCandidate(
        "api","api",ServiceRuntimeProvider.DOCKER,RuntimeTargetStatus.RUNNING,null,null,null)));
    when(logs.save(any())).thenAnswer(call->call.getArgument(0));
    var useCases=new ProjectAssetUseCases(projects,assets,connections,logs,mock(AssetInspectionPort.class),discovery,Clock.fixed(now,ZoneOffset.UTC));

    var result=useCases.replaceMonitoring(project,assetId,List.of(
        new ProjectAssetUseCases.MonitoringTarget(LogProviderType.SYSTEMD,"old.service"),
        new ProjectAssetUseCases.MonitoringTarget(LogProviderType.DOCKER,"api"),
        new ProjectAssetUseCases.MonitoringTarget(LogProviderType.FILE,"/var/log/a.log"),
        new ProjectAssetUseCases.MonitoringTarget(LogProviderType.FILE,"/var/log/b.log")));

    assertThat(result).extracting(LogSource::id).contains(unavailable.id());
    verify(discovery,never()).discover(ssh,ServiceRuntimeProvider.SYSTEMD);
    verify(logs).delete(removedFile); verify(logs,never()).delete(unavailable);
    verify(logs,times(3)).save(any());
  }

  @Test void desiredStateRejectsDuplicatesBeforeWriting() {
    var projects=mock(ProjectRepository.class); var assets=mock(ProjectAssetRepository.class);
    var connections=mock(SshConnectionRepository.class); var logs=mock(LogSourceRepository.class);
    UUID project=UUID.randomUUID(), assetId=UUID.randomUUID(), sshId=UUID.randomUUID(); Instant now=Instant.EPOCH;
    when(projects.findById(project)).thenReturn(Optional.of(new Project(project,"p","p",now,now)));
    when(assets.findById(assetId)).thenReturn(Optional.of(new ProjectAsset(assetId,project,"server",sshId,now,now)));
    when(connections.findById(sshId)).thenReturn(Optional.of(new SshConnection(sshId,project,"host","example",22,"user","/key",now,now)));
    when(logs.findByProjectIdAndAssetId(project,assetId)).thenReturn(List.of());
    var useCases=new ProjectAssetUseCases(projects,assets,connections,logs,mock(AssetInspectionPort.class),mock(RuntimeTargetDiscoveryPort.class),Clock.systemUTC());
    var duplicate=List.of(new ProjectAssetUseCases.MonitoringTarget(LogProviderType.FILE,"/var/log/a.log"),new ProjectAssetUseCases.MonitoringTarget(LogProviderType.FILE,"/var/log/a.log"));
    assertThatThrownBy(()->useCases.replaceMonitoring(project,assetId,duplicate)).hasMessageContaining("Duplicate");
    verify(logs,never()).save(any()); verify(logs,never()).delete(any());
  }
}
