package com.sitionix.forgeai.application;
import com.sitionix.forgeai.application.usecase.standalone.StandaloneAgentSessionUseCase;
import com.sitionix.forgeai.domain.port.*;
import com.sitionix.forgeai.domain.model.standalone.StandaloneSessionContracts.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class StandaloneAgentSessionUseCaseTest {
 @Test void forgeAgentLaunchDoesNotConsultOrOverrideGlobalModelProfile(){
  var client=mock(StandaloneAgentSessionClient.class);var knowledge=mock(KnowledgeActiveProfileClient.class);
  var request=new Create(UUID.randomUUID(),"FORGE","agent",List.of(),"hello",null);UUID project=UUID.randomUUID();
  var receipt=new Receipt(UUID.randomUUID(),UUID.randomUUID(),1,true);when(client.create(project,request,null)).thenReturn(receipt);
  assertThat(new StandaloneAgentSessionUseCase(client,knowledge).create(project,request)).isEqualTo(receipt);
  verifyNoInteractions(knowledge);verify(client).create(project,request,null);
 }
}
