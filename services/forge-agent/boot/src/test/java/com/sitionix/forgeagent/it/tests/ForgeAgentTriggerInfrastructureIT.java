package com.sitionix.forgeagent.it.tests;

import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.application.mcp.McpConnectionService;
import com.sitionix.forgeagent.application.trigger.TriggerBindingService;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
class ForgeAgentTriggerInfrastructureIT extends com.sitionix.forgeagent.it.infra.AgentManagementFixture {
 @Autowired com.sitionix.forgeagent.it.infra.ForgeAgentTestManager forgeIt;
 @Autowired JdbcTemplate jdbc;
 @Autowired MockMvc mvc;
 @Autowired WorkflowUseCases workflows;
 @Autowired WorkflowRunUseCases runs;
 @Autowired McpConnectionService connections;
 @Autowired TriggerBindingService bindings;

 @Test void persistsIndependentSelectionAndEnforcesConnectionOwnershipAndNodeCascade() throws Exception {
  UUID project=UUID.randomUUID(), nodeId=UUID.randomUUID(), input=UUID.randomUUID(), output=UUID.randomUUID();
  jdbc.update("INSERT INTO agent_projects(id,name,normalized_name,created_at,updated_at) VALUES(?,?,?,now(),now())",project,"Trigger test "+project,"trigger test "+project);
  var a=connections.create("Connection A",URI.create("https://example.test/a"),McpAuthType.NONE,McpProjectAccess.selected(Set.of(project)),null,null,null);
  var b=connections.create("Connection B",URI.create("https://example.test/b"),McpAuthType.NONE,McpProjectAccess.selected(Set.of(project)),null,null,null);
  UUID configuration=UUID.randomUUID(), foreign=UUID.randomUUID();
  jdbc.update("INSERT INTO mcp_trigger_configurations(id,connection_id,name) VALUES(?,?,?)",configuration,a.id(),"Configuration A");
  jdbc.update("INSERT INTO mcp_trigger_configurations(id,connection_id,name) VALUES(?,?,?)",foreign,b.id(),"Configuration B");
  var workflow=workflows.createWorkflow(project,new CreateWorkflowCommand("Trigger workflow"));
  var node=new Node(nodeId,null,NodeInputMode.DEPENDENCIES_ONLY,
   List.of(new NodePort(input,"Start","Trigger entry",0)),List.of(new NodePort(output,"Item","Selected item",0)),
   new NodePosition(0,0),NodeScopeMode.GLOBAL,NodeContextMode.FRESH_EACH_NODE_RUN,null,NodeType.TRIGGER);
  var command=new SaveWorkflowCommand(workflow.name(),List.of(node),List.of(),input,output);
  workflows.updateWorkflow(workflow.id(),command);
  String bindingPath="/api/v1/workflows/"+workflow.id()+"/nodes/"+nodeId+"/trigger-binding";
  mvc.perform(put(bindingPath).contentType("application/json").content("{\"connectionId\":\""+a.id()+"\",\"configurationId\":\""+configuration+"\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.configurationId").value(configuration.toString()));
  workflows.updateWorkflow(workflow.id(),command);
  mvc.perform(get(bindingPath)).andExpect(status().isOk()).andExpect(jsonPath("$.connectionId").value(a.id().toString()));
  mvc.perform(get("/api/v1/projects/"+project+"/trigger-connections/"+a.id()+"/configurations"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(configuration.toString()));
  mvc.perform(put(bindingPath).contentType("application/json").content("{\"connectionId\":\""+a.id()+"\",\"configurationId\":\""+foreign+"\"}"))
   .andExpect(status().isBadRequest());
  assertThat(bindings.getBinding(workflow.id(),nodeId).configurationId()).isEqualTo(configuration);
  assertThatThrownBy(()->runs.createWorkflowRun(workflow.id(),new CreateWorkflowRunCommand("Do not run")))
   .isInstanceOfSatisfying(ValidationException.class,e->assertThat(e.code()).isEqualTo("TRIGGER_EXECUTION_NOT_AVAILABLE"));
  UUID storageId=jdbc.queryForObject("SELECT row_id FROM workflow_nodes WHERE workflow_id=? AND id=?",UUID.class,workflow.id(),nodeId);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM node_trigger_bindings WHERE node_row_id=?",Integer.class,storageId)).isEqualTo(1);
  // Logical node IDs remain reusable in different workflows; bindings must not cross that boundary.
  var other=workflows.createWorkflow(project,new CreateWorkflowCommand("Other trigger workflow"));
  UUID otherInput=UUID.randomUUID(),otherOutput=UUID.randomUUID();
  var otherNode=new Node(nodeId,null,NodeInputMode.DEPENDENCIES_ONLY,
   List.of(new NodePort(otherInput,"Start","Trigger entry",0)),List.of(new NodePort(otherOutput,"Item","Selected item",0)),
   new NodePosition(0,0),NodeScopeMode.GLOBAL,NodeContextMode.FRESH_EACH_NODE_RUN,null,NodeType.TRIGGER);
  workflows.updateWorkflow(other.id(),new SaveWorkflowCommand(other.name(),List.of(otherNode),List.of(),otherInput,otherOutput));
  assertThat(bindings.getBinding(other.id(),nodeId).connectionId()).isNull();
  bindings.saveBinding(other.id(),nodeId,b.id(),foreign);
  assertThat(bindings.getBinding(workflow.id(),nodeId).configurationId()).isEqualTo(configuration);
  assertThat(bindings.getBinding(other.id(),nodeId).configurationId()).isEqualTo(foreign);
  workflows.deleteWorkflow(workflow.id());
  assertThat(jdbc.queryForObject("SELECT count(*) FROM node_trigger_bindings WHERE node_row_id=?",Integer.class,storageId)).isZero();
  assertThat(bindings.getBinding(other.id(),nodeId).configurationId()).isEqualTo(foreign);
 }
}
