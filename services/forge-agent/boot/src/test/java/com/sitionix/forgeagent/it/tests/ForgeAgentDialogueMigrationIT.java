package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class ForgeAgentDialogueMigrationIT {
  @Test
  void upgradesPreDialogueGraphWithoutChangingOrdinaryContextOrPorts() {
    try (var database = new PostgreSQLContainer<>("postgres:16-alpine")) {
      database.start();
      var source =
          new DriverManagerDataSource(
              database.getJdbcUrl(), database.getUsername(), database.getPassword());
      Flyway.configure().dataSource(source).target("43").load().migrate();
      var jdbc = new JdbcTemplate(source);
      UUID project = UUID.randomUUID(),
          workflow = UUID.randomUUID(),
          node = UUID.randomUUID(),
          port = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO agent_projects(id,name,normalized_name,created_at,updated_at)"
              + " VALUES(?,'Legacy','legacy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          project);
      jdbc.update(
          "INSERT INTO agent_workflows(id,project_id,name,normalized_name,created_at,updated_at)"
              + " VALUES(?,?,'Legacy','legacy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          workflow,
          project);
      jdbc.update(
          "INSERT INTO"
              + " workflow_nodes(id,workflow_id,target_id,node_type,input_mode,scope_mode,context_mode,position_x,position_y,include_task_repositories)"
              + " VALUES(?,?,NULL,'MANUAL','DEPENDENCIES_ONLY','GLOBAL','FRESH_EACH_NODE_RUN',0,0,TRUE)",
          node,
          workflow);
      jdbc.update(
          "INSERT INTO"
              + " workflow_node_ports(id,workflow_id,node_id,direction,name,description,port_order)"
              + " VALUES(?,?,?,'OUTPUT','Continue','Continue',0)",
          port,
          workflow,
          node);
      var result = Flyway.configure().dataSource(source).load().migrate();
      assertThat(result.migrationsExecuted).isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "SELECT context_mode FROM workflow_nodes WHERE id=?", String.class, node))
          .isEqualTo("FRESH_EACH_NODE_RUN");
      assertThat(
              jdbc.queryForObject(
                  "SELECT dialogue_disposition FROM workflow_node_ports WHERE id=?",
                  String.class,
                  port))
          .isNull();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM dialogues", Long.class)).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT description FROM workflow_node_ports WHERE id=?", String.class, port))
          .isEqualTo("Continue");
    }
  }
}
