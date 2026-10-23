package com.sitionix.forgeagent.api.library;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.library.GetAiLibraryUseCase;
import com.sitionix.forgeagent.domain.model.library.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiLibraryControllerTest {
 @Test void deniesRequestsOutsideAuthenticatedServerBoundary(){var controller=new AiLibraryController(mock(GetAiLibraryUseCase.class),new ObjectMapper());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.query(UUID.randomUUID(),new MockHttpServletRequest()));}
 @Test void responseOmitsNativePathsAndInternalIdentity() throws Exception {
  var id=UUID.randomUUID();var skill=new SkillDescriptor("opaque","name","safe",new LibraryProvenance("repo","/private/token"));
  var snapshot=new LibrarySnapshot(id,new LibraryProviderContext(1,"codex","model",null,"Codex","Model"),new LibraryCategories(InventoryResult.available(List.of(skill)),InventoryResult.unsupported(),InventoryResult.unsupported(),InventoryResult.unavailable(InventoryDiagnostic.MCP_EXECUTION_CONTEXT_REQUIRED)));
  var output=new ObjectMapper().writeValueAsString(AiLibraryResponse.from(snapshot));assertTrue(output.contains("opaque"));assertFalse(output.contains("/private"));assertFalse(output.contains("nativeIdentity"));assertTrue(output.contains("UNSUPPORTED"));
 }
}
