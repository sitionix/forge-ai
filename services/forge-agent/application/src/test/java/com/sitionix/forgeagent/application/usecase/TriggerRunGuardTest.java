package com.sitionix.forgeagent.application.usecase;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class TriggerRunGuardTest {
 @Test void rejectsUnimplementedTriggerExecutionBeforeBuildingSnapshot() {
  UUID id=UUID.randomUUID(); var repository=mock(WorkflowRepository.class);
  var node=new Node(UUID.randomUUID(),null,NodeInputMode.DEPENDENCIES_ONLY,List.of(),List.of(),new NodePosition(0,0),NodeScopeMode.GLOBAL,NodeContextMode.FRESH_EACH_NODE_RUN,null,NodeType.valueOf("TRIGGER"));
  var workflow=new Workflow(id,UUID.randomUUID(),"Trigger","trigger",List.of(node),List.of(),null,null,Instant.EPOCH,Instant.EPOCH);
  when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(workflow));
  var service=new WorkflowRunUseCases(repository,null,null,null,null,null,null,null,null,Clock.systemUTC());
  assertThatThrownBy(()->service.createWorkflowRun(id,new CreateWorkflowRunCommand("test")))
   .isInstanceOfSatisfying(ValidationException.class,e->assertThat(e.code()).isEqualTo("TRIGGER_EXECUTION_NOT_AVAILABLE"));
 }
}
