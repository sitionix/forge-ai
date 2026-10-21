package com.sitionix.forgeagent.it.tests;

import com.sitionix.forgeagent.it.infra.AgentManagementFixture;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.library.*;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring API/filter/application/workspace/DB composition; deterministic provider only. */
@IntegrationTest
class ForgeAgentAiLibraryIT extends AgentManagementFixture {
 @Autowired com.sitionix.forgeagent.it.infra.ForgeAgentTestManager forgeIt;
 @Autowired MockMvc mvc;
 @Autowired ProjectRepository projects;
 @MockBean LibraryProviderSessionFactory provider;
 static final String TOKEN="i".repeat(32);
 static {try {Files.writeString(ROOT.resolve("library-service"),TOKEN);Files.setAttribute(ROOT.resolve("library-service"),"unix:mode",0600);}catch(Exception failure){throw new IllegalStateException(failure);}}
 @org.springframework.test.context.DynamicPropertySource
 static void libraryIdentity(org.springframework.test.context.DynamicPropertyRegistry properties) {
  properties.add("server.address",()->"127.0.0.1");
  properties.add("server.forward-headers-strategy",()->"none");
  properties.add("forge.ai-library.service-token-file",()->ROOT.resolve("library-service").toString());
 }
 @BeforeEach void fixture(){
  when(provider.supports("codex")).thenReturn(true);
  when(provider.open(any())).thenAnswer(call->{
   var context=call.<LibraryContext>getArgument(0);
   return new LibraryProviderSession(){
    public SkillInventoryPort skills(){return c->InventoryResult.available(List.of(new SkillDescriptor(c.projectId().toString(),"Native fixture","Project inventory",new LibraryProvenance("repo",context.cwd().toString()))));}
    public AgentInventoryPort agents(){return c->InventoryResult.unsupported();}
    public FlowInventoryPort flows(){return c->InventoryResult.unsupported();}
    public ToolInventoryPort tools(){return c->InventoryResult.unavailable(InventoryDiagnostic.MCP_EXECUTION_CONTEXT_REQUIRED);}
    public void close(){}
   };
  });
 }
 @Test void projectIsolationAndSafeSerializationThroughAuthenticatedBoundary() throws Exception {
  var a=create("Library A");var b=create("Library B");
  for(var project:List.of(a,b))mvc.perform(post(route(project.id())).header("Authorization","Bearer "+TOKEN).contentType("application/json").content(body()))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
   .andExpect(jsonPath("$.projectId").value(project.id().toString())).andExpect(jsonPath("$.categories.skills.items[0].id").value(project.id().toString()))
   .andExpect(jsonPath("$.categories.skills.items[0].nativeIdentity").doesNotExist()).andExpect(jsonPath("$.categories.agents.status").value("UNSUPPORTED"));
  verify(provider).open(argThat(c->c.projectId().equals(a.id())&&c.cwd().toString().contains(a.id().toString())));
  verify(provider).open(argThat(c->c.projectId().equals(b.id())&&c.cwd().toString().contains(b.id().toString())));
 }
 @Test void rejectsUnauthorizedBrowserAndMissingProjects() throws Exception {
  var p=create("Library Access");mvc.perform(post(route(p.id())).contentType("application/json").content(body())).andExpect(status().isUnauthorized());
  mvc.perform(post(route(p.id())).header("Authorization","Bearer "+TOKEN).header("Origin","http://localhost").contentType("application/json").content(body())).andExpect(status().isForbidden());
  mvc.perform(post(route(UUID.randomUUID())).header("Authorization","Bearer "+TOKEN).contentType("application/json").content(body())).andExpect(status().isNotFound());
  verify(provider,never()).open(any());
 }
 private Project create(String name){var now=Instant.now();return projects.save(new Project(UUID.randomUUID(),name,name.toLowerCase(Locale.ROOT),now,now));}
 private String route(UUID id){return "/api/v1/projects/"+id+"/ai-library/query";}
 private String body(){return "{\"provider\":{\"profileRevision\":1,\"providerId\":\"codex\",\"modelId\":\"selected\"}}";}
}
