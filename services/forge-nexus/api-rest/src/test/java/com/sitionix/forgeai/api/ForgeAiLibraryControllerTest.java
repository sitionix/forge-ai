package com.sitionix.forgeai.api;
import com.sitionix.forgeai.domain.model.library.*;
import com.sitionix.forgeai.domain.usecase.GetAiLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
class ForgeAiLibraryControllerTest {
 @Test void publicGetUsesOnlyTrustedProjectAndReturnsTypedSnapshotNoStore() throws Exception {
  var id=UUID.randomUUID();var query=mock(GetAiLibrary.class);when(query.execute(id)).thenReturn(new LibrarySnapshot(id,12L,new LibraryProviderContext(12,"selected","m",null,null,null),LibrarySnapshot.Categories.unavailable()));
  var mvc=MockMvcBuilders.standaloneSetup(new ForgeAiLibraryController(query)).build();
  mvc.perform(get("/api/v1/infrastructure/agents/projects/{id}/ai-library",id).param("providerId","untrusted").param("cwd","/untrusted"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.provider.providerId").value("selected")).andExpect(jsonPath("$.categories.skills.status").value("UNAVAILABLE"));
  verify(query).execute(id);
 }
}
