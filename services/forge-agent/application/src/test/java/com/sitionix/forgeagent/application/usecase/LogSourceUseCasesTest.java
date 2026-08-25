package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.domain.exception.*;import com.sitionix.forgeagent.domain.model.*;import com.sitionix.forgeagent.domain.port.*;
import java.time.*;import java.util.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.extension.ExtendWith;import org.mockito.*;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class LogSourceUseCasesTest {
 @Mock ProjectRepository projects;@Mock LogSourceRepository sources;@Mock SshConnectionRepository connections;@Mock DockerLogPort docker;@Mock RemoteLogPort remote;
 UUID projectId=UUID.randomUUID();LogSourceUseCases useCases;
 @BeforeEach void setup(){useCases=new LogSourceUseCases(projects,sources,connections,docker,remote,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));when(projects.findById(projectId)).thenReturn(Optional.of(new Project(projectId,"P","p",Instant.EPOCH,Instant.EPOCH)));lenient().when(sources.save(any())).thenAnswer(i->i.getArgument(0));}
 @Test void customSourceBelongsToProjectAndNeedsNoService(){var result=useCases.create(projectId,command("one",null));assertThat(result.projectId()).isEqualTo(projectId);assertThat(result.serviceId()).isNull();}
 @Test void multipleSourcesCanBeCreatedWithoutOnePerServiceConstraint(){useCases.create(projectId,command("one",null));useCases.create(projectId,command("two",null));verify(sources,times(2)).save(any());}
 @Test void crossProjectSourceIsHidden(){UUID other=UUID.randomUUID();var source=new LogSource(UUID.randomUUID(),other,"x",null,LogConnectionType.LOCAL,null,LogProviderType.DOCKER,new DockerLogConfiguration("c",null,null),true,Instant.EPOCH,Instant.EPOCH);when(sources.findById(source.id())).thenReturn(Optional.of(source));assertThatThrownBy(()->useCases.delete(projectId,source.id())).isInstanceOf(NotFoundException.class);}
 @Test void serviceAssociationFailsExplicitlyWhenServiceModelIsUnavailable(){assertThatThrownBy(()->useCases.create(projectId,command("one",UUID.randomUUID()))).isInstanceOf(ValidationException.class).hasMessageContaining("no Service resource");}
 private SaveLogSourceCommand command(String name,UUID service){return new SaveLogSourceCommand(name,service,LogConnectionType.LOCAL,null,LogProviderType.DOCKER,new DockerLogConfiguration("container",null,null),true);}
}
