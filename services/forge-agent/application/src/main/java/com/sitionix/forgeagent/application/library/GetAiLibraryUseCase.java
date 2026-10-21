package com.sitionix.forgeagent.application.library;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.port.library.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class GetAiLibraryUseCase {
 private final ProjectRepository projects;
 private final ProjectRepositoryLinkRepository links;
 private final LocalProjectWorkspacePort workspace;
 private final GitRepositoryPort git;
 private final List<LibraryProviderSessionFactory> providers;
 public GetAiLibraryUseCase(ProjectRepository projects, ProjectRepositoryLinkRepository links, LocalProjectWorkspacePort workspace,
                            GitRepositoryPort git, List<LibraryProviderSessionFactory> providers) {
  this.projects=projects;this.links=links;this.workspace=workspace;this.git=git;this.providers=List.copyOf(providers);
 }
 public LibrarySnapshot execute(UUID projectId, LibraryProviderContext provider) {
  projects.findById(projectId).orElseThrow(()->new NotFoundException("PROJECT_NOT_FOUND","Project was not found."));
  if(provider==null) return unavailable(projectId,null,InventoryDiagnostic.PROFILE_UNAVAILABLE);
  var matching=providers.stream().filter(p->p.supports(provider.providerId())).toList();
  if(matching.isEmpty()) return new LibrarySnapshot(projectId,provider,LibraryCategories.unsupported());
  if(matching.size()!=1) return unavailable(projectId,provider,InventoryDiagnostic.RUNTIME_UNAVAILABLE);
  final LibraryContext context;
  try {
   var cwd=workspace.resolveProjectWorkspace(projectId);
   var repositories=links.findByProjectId(projectId);
   if(repositories.size()>31) return unavailable(projectId,provider,InventoryDiagnostic.LIMIT_EXCEEDED);
   var references=repositories.stream().map(r->new ProjectRepositoryWorkspaceReference(r.id(),git.resolveRepositoryName(r.remoteUrl()))).toList();
   var states=workspace.resolveRepositoryWorkspaceStates(projectId,references);
   var roots=new ArrayList<java.nio.file.Path>();roots.add(cwd);
   for(var reference:references) { var state=states.get(reference.id());if(state!=null&&state.cloned()) roots.add(state.path()); }
   context=new LibraryContext(projectId,provider,cwd,roots);
  } catch(RuntimeException unavailable) {return unavailable(projectId,provider,InventoryDiagnostic.WORKSPACE_UNAVAILABLE);}
  try(var session=matching.getFirst().open(context)) {
   return new LibrarySnapshot(projectId,provider,new LibraryCategories(
    safely(()->session.skills().discover(context)),safely(()->session.agents().discover(context)),
    safely(()->session.flows().discover(context)),safely(()->session.tools().discover(context))));
  } catch(RuntimeException unavailable) {return unavailable(projectId,provider,InventoryDiagnostic.RUNTIME_UNAVAILABLE);}
 }
 private <T> InventoryResult<T> safely(Supplier<InventoryResult<T>> discovery) {try{return Objects.requireNonNull(discovery.get());}catch(RuntimeException failure){return InventoryResult.failed();}}
 private LibrarySnapshot unavailable(UUID id,LibraryProviderContext provider,InventoryDiagnostic reason){return new LibrarySnapshot(id,provider,LibraryCategories.unavailable(reason));}
}
