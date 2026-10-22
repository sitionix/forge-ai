package com.sitionix.forgeagent.application;
import com.sitionix.forgeagent.application.definition.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StandaloneDefinitionResolverTest {
 @Test void forgeModelIsNeverReplacedByProfileAndSnapshotIsIndependentOfSourceEdits() {
  var agents=mock(AgentDefinitionRepository.class);var nativePort=mock(NativeAgentDefinitionPort.class);
  UUID project=UUID.randomUUID(),id=UUID.randomUUID();
  var source=new AgentDefinition(id,project,"Reviewer","reviewer","Review carefully",null,new AgentModelSelection("codex","source-model","high"),Instant.EPOCH,Instant.EPOCH);
  when(agents.findById(id)).thenReturn(Optional.of(source));
  var resolver=new AgentDefinitionResolver(agents,nativePort);
  var snapshot=resolver.resolve(project,"FORGE",id.toString(),null,new AgentModelSelection("codex","global-model",null));
  assertThat(snapshot.model()).isEqualTo(source.model());assertThat(snapshot.instructions()).isEqualTo("Review carefully");
  when(agents.findById(id)).thenReturn(Optional.of(new AgentDefinition(id,project,"Changed","changed","new instructions",null,source.model(),Instant.EPOCH,Instant.now())));
  assertThat(snapshot.instructions()).isEqualTo("Review carefully");
  assertThat(snapshot.fingerprint()).isNotEqualTo(resolver.resolve(project,"FORGE",id.toString(),null,null).fingerprint());
  assertThatThrownBy(()->resolver.resolve(UUID.randomUUID(),"FORGE",id.toString(),null,null)).hasMessageContaining("not found");
  verifyNoInteractions(nativePort);
 }
}
