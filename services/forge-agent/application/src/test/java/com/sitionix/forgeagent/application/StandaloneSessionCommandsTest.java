package com.sitionix.forgeagent.application;
import com.sitionix.forgeagent.application.definition.AgentDefinitionResolver;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.standalone.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.execution.*;
import com.sitionix.forgeagent.domain.model.standalone.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.port.standalone.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StandaloneSessionCommandsTest {
 @Test void createsStandaloneOwnerUsingAgentSnapshotWithoutWorkflowDependencies() {
  var repository=mock(StandaloneSessionRepository.class);var projects=mock(ProjectRepository.class);var spaces=mock(ExecutionWorkspaceResolver.class);var definitions=mock(AgentDefinitionResolver.class);
  UUID project=UUID.randomUUID();when(projects.findById(project)).thenReturn(Optional.of(new Project(project,"Project","project",java.time.Instant.EPOCH,java.time.Instant.EPOCH)));
  var workspace=new ExecutionWorkspace(Path.of("/tmp/project"),List.of());when(spaces.resolve(project,null,List.of())).thenReturn(workspace);
  var definition=new EffectiveAgentDefinition("FORGE","agent","Agent","own instructions",new AgentModelSelection("codex","own-model","high"),"snapshot",Map.of(),false);
  when(definitions.resolve(project,"FORGE","agent",workspace,null)).thenReturn(definition);
  when(repository.create(any(),any(),any(),any())).thenAnswer(i->{StandaloneAgentSession s=i.getArgument(0);assertThat(s.definition()).isEqualTo(definition);assertThat(s.projectId()).isEqualTo(project);assertThat(s.providerConversationId()).isNull();return new StandaloneCommandReceipt(s.id(),UUID.randomUUID(),1,true);});
  var commands=new StandaloneSessionCommands(repository,projects,spaces,definitions);
  assertThat(commands.create(project,new StandaloneSessionCommands.Create(UUID.randomUUID(),"FORGE","agent",List.of(),"first task",null),null).accepted()).isTrue();
  verify(repository).create(any(),any(),any(),eq("first task"));
 }
}
