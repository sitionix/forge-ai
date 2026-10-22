package com.sitionix.forgeagent.api;
import com.sitionix.forgeagent.api.standalone.StandaloneSessionApiMapper;
import com.sitionix.forgeagent.domain.model.standalone.StandaloneAgentEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class StandaloneSessionEventPrivacyTest {
 @Test void publicActivityOmitsInternalPathsRawToolOutputAndInstructions(){
  var event=new StandaloneAgentEvent(UUID.randomUUID(),UUID.randomUUID(),1,"COMMAND","COMPLETED",null,"{\"command\":\"cat /srv/private/agent/SKILL.md\",\"cwd\":\"/srv/private\",\"output\":\"private instructions credential-canary\",\"exitCode\":0}",Instant.now());
  String payload=StandaloneSessionApiMapper.map(event).payload();
  assertThat(payload).doesNotContain("/srv/private","private instructions","credential-canary","cwd","output").contains("command","exitCode","runtime-path");
 }
}
