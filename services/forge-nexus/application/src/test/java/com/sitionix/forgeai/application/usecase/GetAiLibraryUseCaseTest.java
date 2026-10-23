package com.sitionix.forgeai.application.usecase;
import com.sitionix.forgeai.domain.model.activeprofile.*;
import com.sitionix.forgeai.domain.model.library.*;
import com.sitionix.forgeai.domain.port.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class GetAiLibraryUseCaseTest {
 @Test void resolvesKnowledgeOnceAndPassesImmutableSelection(){
  var knowledge=mock(KnowledgeActiveProfileClient.class);var client=mock(AiLibraryClient.class);var id=UUID.randomUUID();
  when(knowledge.getActiveProfile()).thenReturn(new ActiveProfile(19,new ActiveLlmProfile("codex","selected",null,"Codex","Selected"),null));
  when(client.query(eq(id),any())).thenAnswer(i->new LibrarySnapshot(id,19L,i.getArgument(1),LibrarySnapshot.Categories.unavailable()));
  var result=new GetAiLibraryUseCase(knowledge,client).execute(id);assertEquals("selected",result.provider().modelId());assertEquals(19,result.profileRevision());verify(knowledge,times(1)).getActiveProfile();verify(client).query(eq(id),argThat(p->p.profileRevision()==19&&p.providerId().equals("codex")));
 }
 @Test void unavailableKnowledgeStillDelegatesProjectValidationWithoutProvider(){var knowledge=mock(KnowledgeActiveProfileClient.class);var client=mock(AiLibraryClient.class);when(knowledge.getActiveProfile()).thenThrow(new IllegalStateException("private"));var id=UUID.randomUUID();new GetAiLibraryUseCase(knowledge,client).execute(id);verify(client).query(id,null);}
}
