package com.sitionix.forgeagent.api;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.standalone.StandaloneAgentSessionController;
import com.sitionix.forgeagent.application.standalone.*;
import com.sitionix.forgeagent.domain.model.standalone.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class StandaloneAgentSessionControllerTest {
 @Test void authenticatedMessageIsAcknowledgedWithoutWaitingForExecution() throws Exception {
  var commands=mock(StandaloneSessionCommands.class);var queries=mock(StandaloneSessionQueries.class);
  UUID project=UUID.randomUUID(),session=UUID.randomUUID(),turn=UUID.randomUUID();
  when(commands.send(eq(project),eq(session),any())).thenReturn(new StandaloneCommandReceipt(session,turn,2,true));
  var mvc=MockMvcBuilders.standaloneSetup(new StandaloneAgentSessionController(commands,queries)).setControllerAdvice(new ForgeAgentExceptionHandler()).build();
  mvc.perform(post("/api/v1/projects/"+project+"/agent-sessions/"+session+"/messages").requestAttr("forgeStandaloneServiceAuthenticated",true).contentType("application/json")
    .content("{\"requestId\":\""+UUID.randomUUID()+"\",\"expectedRevision\":1,\"text\":\"hello\"}"))
   .andExpect(status().isAccepted()).andExpect(jsonPath("turnId").value(turn.toString())).andExpect(jsonPath("revision").value(2));
  verify(commands).send(eq(project),eq(session),any());
 }
 @Test void absentServiceIdentityCannotStartExecution() throws Exception {
  var commands=mock(StandaloneSessionCommands.class);var queries=mock(StandaloneSessionQueries.class);
  var mvc=MockMvcBuilders.standaloneSetup(new StandaloneAgentSessionController(commands,queries)).build();
  mvc.perform(get("/api/v1/projects/"+UUID.randomUUID()+"/agent-sessions")).andExpect(status().isForbidden());
  verifyNoInteractions(commands,queries);
 }
}
