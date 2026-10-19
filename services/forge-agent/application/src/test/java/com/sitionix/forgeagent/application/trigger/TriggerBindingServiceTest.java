package com.sitionix.forgeagent.application.trigger;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.exception.*;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TriggerBindingServiceTest {
 private final UUID project=UUID.randomUUID(),workflowId=UUID.randomUUID(),nodeId=UUID.randomUUID(),connectionId=UUID.randomUUID(),owner=UUID.randomUUID(),configurationId=UUID.randomUUID();
 private final WorkflowRepository workflows=mock(WorkflowRepository.class);
 private final McpConnectionRepository connections=mock(McpConnectionRepository.class);
 private final ForgeInstanceIdentityRepository identity=mock(ForgeInstanceIdentityRepository.class);
 private final ProjectRepository projects=mock(ProjectRepository.class);
 private final MemoryBindings bindings=new MemoryBindings();
 private final TriggerBindingService service=new TriggerBindingService(workflows,connections,identity,projects,bindings);
 private McpConnection connection(McpProjectAccess access) { return new McpConnection(connectionId,owner,"MCP",java.net.URI.create("https://example.test/mcp"),McpAuthType.NONE,true,access,Set.of(),false,Instant.EPOCH,Instant.EPOCH,null,null,null,null); }
 @BeforeEach void setup() {
  var node=new Node(nodeId,null,NodeInputMode.DEPENDENCIES_ONLY,List.of(),List.of(),new NodePosition(0,0),NodeScopeMode.GLOBAL,NodeContextMode.FRESH_EACH_NODE_RUN,null,NodeType.TRIGGER);
  var workflow=new Workflow(workflowId,project,"Test","test",List.of(node),List.of(),null,null,Instant.EPOCH,Instant.EPOCH);
  when(workflows.findById(workflowId)).thenReturn(Optional.of(workflow));
  when(workflows.findByIdForUpdate(workflowId)).thenReturn(Optional.of(workflow));
  when(identity.getOrCreate()).thenReturn(owner);
  when(connections.findById(owner,connectionId)).thenReturn(Optional.of(connection(McpProjectAccess.selected(Set.of(project)))));
  bindings.configurations.put(configurationId,new TriggerConfiguration(configurationId,connectionId,"Existing configuration"));
 }
 @Test void savesAndReloadsAnIndependentBinding() {
  service.saveBinding(workflowId,nodeId,connectionId,configurationId);
  assertThat(service.getBinding(workflowId,nodeId)).isEqualTo(new TriggerBinding(nodeId,connectionId,configurationId));
  service.saveBinding(workflowId,nodeId,null,null);
  assertThat(service.getBinding(workflowId,nodeId).connectionId()).isNull();
 }
 @Test void rejectsConnectionWithoutProjectAccess() {
  when(connections.findById(owner,connectionId)).thenReturn(Optional.of(connection(McpProjectAccess.selected(Set.of(UUID.randomUUID())))));
  assertThatThrownBy(()->service.saveBinding(workflowId,nodeId,connectionId,null)).isInstanceOf(ValidationException.class);
  assertThat(bindings.rows).isEmpty();
 }
 @Test void rejectsConfigurationFromAnotherConnection() {
  bindings.configurations.put(configurationId,new TriggerConfiguration(configurationId,UUID.randomUUID(),"Foreign"));
  assertThatThrownBy(()->service.saveBinding(workflowId,nodeId,connectionId,configurationId)).isInstanceOf(ValidationException.class);
  assertThat(bindings.rows).isEmpty();
 }
 @Test void rejectsNodeOutsideWorkflow() {
  assertThatThrownBy(()->service.saveBinding(workflowId,UUID.randomUUID(),connectionId,null)).isInstanceOf(NotFoundException.class);
 }
 static class MemoryBindings implements TriggerBindingRepository {
  final Map<UUID,TriggerBinding> rows=new HashMap<>(); final Map<UUID,TriggerConfiguration> configurations=new HashMap<>();
  public Optional<TriggerBinding> findByNodeId(UUID workflowId,UUID id){return Optional.ofNullable(rows.get(id));}
  public List<TriggerConfiguration> findConfigurations(UUID connectionId){return configurations.values().stream().filter(c->c.connectionId().equals(connectionId)).toList();}
  public Optional<TriggerConfiguration> findConfiguration(UUID id){return Optional.ofNullable(configurations.get(id));}
  public void save(UUID workflowId,TriggerBinding binding){rows.put(binding.nodeId(),binding);}
  public void delete(UUID workflowId,UUID nodeId){rows.remove(nodeId);}
 }
}
