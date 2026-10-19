package com.sitionix.forgeagent.application.trigger;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.exception.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
// Connection lookup initializes the installation identity through getOrCreate().
@Transactional
public class TriggerBindingService {
 private final WorkflowRepository workflows;
 private final McpConnectionRepository connections;
 private final ForgeInstanceIdentityRepository identity;
 private final ProjectRepository projects;
 private final TriggerBindingRepository bindings;
 public TriggerBindingService(WorkflowRepository workflows,McpConnectionRepository connections,
   ForgeInstanceIdentityRepository identity,ProjectRepository projects,TriggerBindingRepository bindings) {
  this.workflows=workflows;this.connections=connections;this.identity=identity;this.projects=projects;this.bindings=bindings;
 }
 public List<TriggerConnection> listConnections(UUID projectId) {
  requireProject(projectId);
  return connections.findAll(identity.getOrCreate()).stream().filter(c->c.projectAccess().allows(projectId))
   .map(c->new TriggerConnection(c.id(),c.displayName(),c.enabled())).toList();
 }
 public List<TriggerConfiguration> listConfigurations(UUID projectId,UUID connectionId) {
  requireProject(projectId);requireConnection(projectId,connectionId);
  return bindings.findConfigurations(connectionId);
 }
 public TriggerBinding getBinding(UUID workflowId,UUID nodeId) {
  var workflow=requireNode(workflows.findById(workflowId),nodeId);
  var binding=bindings.findByNodeId(workflowId,nodeId).orElse(new TriggerBinding(nodeId,null,null));
  if(binding.connectionId()!=null) requireConnection(workflow.projectId(),binding.connectionId());
  return binding;
 }
 @Transactional
 public TriggerBinding saveBinding(UUID workflowId,UUID nodeId,UUID connectionId,UUID configurationId) {
  var workflow=requireNode(workflows.findByIdForUpdate(workflowId),nodeId);
  if(connectionId==null) {
   if(configurationId!=null) throw invalid("Choose a connection before its configuration.");
   bindings.delete(workflowId,nodeId);return new TriggerBinding(nodeId,null,null);
  }
  requireConnection(workflow.projectId(),connectionId);
  if(configurationId!=null) {
   var configuration=bindings.findConfiguration(configurationId)
    .orElseThrow(()->new NotFoundException("TRIGGER_CONFIGURATION_NOT_FOUND","Trigger configuration was not found."));
   if(!connectionId.equals(configuration.connectionId())) throw invalid("Configuration does not belong to the selected connection.");
  }
  var binding=new TriggerBinding(nodeId,connectionId,configurationId);bindings.save(workflowId,binding);return binding;
 }
 private Workflow requireNode(Optional<Workflow> candidate,UUID nodeId) {
  var workflow=candidate.orElseThrow(()->new NotFoundException("WORKFLOW_NOT_FOUND","Workflow was not found."));
  var node=workflow.nodes().stream().filter(n->n.id().equals(nodeId)).findFirst()
   .orElseThrow(()->new NotFoundException("WORKFLOW_NODE_NOT_FOUND","Workflow node was not found."));
  if(node.nodeType()!=NodeType.TRIGGER) throw invalid("Only a Trigger node can select a trigger configuration.");
  return workflow;
 }
 private void requireProject(UUID projectId) {
  if(projects.findById(projectId).isEmpty()) throw new NotFoundException("PROJECT_NOT_FOUND","Project was not found.");
 }
 private McpConnection requireConnection(UUID projectId,UUID connectionId) {
  var connection=connections.findById(identity.getOrCreate(),connectionId)
   .orElseThrow(()->new NotFoundException("MCP_CONNECTION_NOT_FOUND","MCP connection was not found."));
  if(!connection.projectAccess().allows(projectId)) throw invalid("Connection is not available to this project.");
  return connection;
 }
 private static ValidationException invalid(String message){return new ValidationException("INVALID_TRIGGER_BINDING",message);}
}
