package com.sitionix.forgeagent.application.library;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.port.library.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class GetAiLibraryUseCaseTest {
 final ProjectRepository projects=mock(ProjectRepository.class);
 final ProjectRepositoryLinkRepository links=mock(ProjectRepositoryLinkRepository.class);
 final LocalProjectWorkspacePort workspace=mock(LocalProjectWorkspacePort.class);
 final GitRepositoryPort git=mock(GitRepositoryPort.class);
 final LibraryProviderSessionFactory factory=mock(LibraryProviderSessionFactory.class);
 final LibraryProviderContext provider=new LibraryProviderContext(7,"codex","m",null,"Codex","M");
 GetAiLibraryUseCase useCase(){return new GetAiLibraryUseCase(projects,links,workspace,git,List.of(factory));}
 void project(UUID id){when(projects.findById(id)).thenReturn(Optional.of(new Project(id,"p","p",null,null)));when(links.findByProjectId(id)).thenReturn(List.of());when(workspace.resolveProjectWorkspace(id)).thenReturn(Path.of("/managed",id.toString()));}
 @Test void rejectsMissingProjectBeforeRuntimeOrWorkspace(){assertThrows(com.sitionix.forgeagent.domain.exception.NotFoundException.class,()->useCase().execute(UUID.randomUUID(),provider));verifyNoInteractions(factory,workspace);}
 @Test void missingProfileDoesNotLaunchRuntime(){var id=UUID.randomUUID();project(id);assertEquals(InventoryStatus.UNAVAILABLE,useCase().execute(id,null).categories().skills().status());verifyNoInteractions(factory,workspace);}
 @Test void unsupportedProviderIsExplicit(){var id=UUID.randomUUID();project(id);assertEquals(InventoryStatus.UNSUPPORTED,useCase().execute(id,provider).categories().tools().status());verify(factory,never()).open(any());}
 @Test void partialFailurePreservesOtherCategoriesAndClosesOneSession(){
  var id=UUID.randomUUID();project(id);when(factory.supports("codex")).thenReturn(true);
  var session=mock(LibraryProviderSession.class);when(factory.open(any())).thenReturn(session);
  when(session.skills()).thenReturn(c->{throw new IllegalStateException("private path");});
  when(session.agents()).thenReturn(c->InventoryResult.unsupported());when(session.flows()).thenReturn(c->InventoryResult.unsupported());
  when(session.tools()).thenReturn(c->InventoryResult.available(List.of(new ToolDescriptor("id","tool","",null))));
  var result=useCase().execute(id,provider);assertEquals(InventoryStatus.FAILED,result.categories().skills().status());assertEquals(1,result.categories().tools().items().size());verify(session).close();verify(factory).open(argThat(c->c.projectId().equals(id)&&c.provider().profileRevision()==7&&c.cwd().equals(Path.of("/managed",id.toString()))));
 }
 @Test void projectContextsAreResolvedIndependently(){var a=UUID.randomUUID();var b=UUID.randomUUID();project(a);project(b);when(factory.supports("codex")).thenReturn(true);when(factory.open(any())).thenThrow(new IllegalStateException());useCase().execute(a,provider);useCase().execute(b,provider);verify(factory).open(argThat(c->c.cwd().equals(Path.of("/managed",a.toString()))));verify(factory).open(argThat(c->c.cwd().equals(Path.of("/managed",b.toString()))));}
}
