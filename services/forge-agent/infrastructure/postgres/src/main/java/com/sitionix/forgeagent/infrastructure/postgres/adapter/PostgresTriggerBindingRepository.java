package com.sitionix.forgeagent.infrastructure.postgres.adapter;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.TriggerBindingRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class PostgresTriggerBindingRepository implements TriggerBindingRepository {
 private final JdbcTemplate jdbc;
 public PostgresTriggerBindingRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Optional<TriggerBinding> findByNodeId(UUID workflowId,UUID nodeId){
  return jdbc.query("SELECT n.id,b.connection_id,b.configuration_id FROM node_trigger_bindings b JOIN workflow_nodes n ON n.row_id=b.node_row_id WHERE n.workflow_id=? AND n.id=?",
   (r,n)->new TriggerBinding(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),workflowId,nodeId).stream().findFirst();
 }
 public List<TriggerConfiguration> findConfigurations(UUID connectionId){
  return jdbc.query("SELECT id,connection_id,name FROM mcp_trigger_configurations WHERE connection_id=? ORDER BY lower(name),id",
   (r,n)->new TriggerConfiguration(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),connectionId);
 }
 public Optional<TriggerConfiguration> findConfiguration(UUID id){
  return jdbc.query("SELECT id,connection_id,name FROM mcp_trigger_configurations WHERE id=?",
   (r,n)->new TriggerConfiguration(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),id).stream().findFirst();
 }
 public void save(UUID workflowId,TriggerBinding binding){
  jdbc.update("INSERT INTO node_trigger_bindings(node_row_id,connection_id,configuration_id) SELECT n.row_id,?,? FROM workflow_nodes n WHERE n.workflow_id=? AND n.id=? AND n.node_type='TRIGGER' ON CONFLICT(node_row_id) DO UPDATE SET connection_id=EXCLUDED.connection_id,configuration_id=EXCLUDED.configuration_id",
   binding.connectionId(),binding.configurationId(),workflowId,binding.nodeId());
 }
 public void delete(UUID workflowId,UUID nodeId){jdbc.update("DELETE FROM node_trigger_bindings WHERE node_row_id IN (SELECT row_id FROM workflow_nodes WHERE workflow_id=? AND id=?)",workflowId,nodeId);}
}
