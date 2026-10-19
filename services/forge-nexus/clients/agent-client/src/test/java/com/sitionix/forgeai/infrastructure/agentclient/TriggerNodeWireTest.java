package com.sitionix.forgeai.infrastructure.agentclient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TriggerNodeWireTest {
 @Test void acceptsAnAgentlessTriggerFromTheAgentApi() throws Exception {
  var json=new ObjectMapper();
  var node=json.readValue("{\"id\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"targetId\":null,\"nodeType\":\"TRIGGER\",\"scopeMode\":\"GLOBAL\",\"inputs\":[],\"outputs\":[]}",NodeResponse.class);
  assertThat(node.nodeType().name()).isEqualTo("TRIGGER");
  assertThat(node.targetId()).isNull();
  assertThat(json.readTree(json.writeValueAsString(node)).path("nodeType").asText()).isEqualTo("TRIGGER");
 }
}
