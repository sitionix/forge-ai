package com.sitionix.forgeai.infrastructure.agentclient.library;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.library.*;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiLibraryClientAdapterTest {
 @TempDir Path directory;
 final ObjectMapper json=new ObjectMapper();
 final UUID id=UUID.randomUUID();
 final LibraryProviderContext provider=new LibraryProviderContext(8,"codex","selected",null,"Codex","Selected");
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true}) void actualHttpComposesTypedRequestAndAuthenticatesWithoutBrowserHeaders(boolean legacy) throws Exception {
  var secret=directory.resolve("secret");String token="a".repeat(legacy?43:32);Files.writeString(secret,token+(legacy?"\n":""));Files.setPosixFilePermissions(secret,PosixFilePermissions.fromString(legacy?"r--------":"rw-------"));
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var bodies=new ArrayList<String>();var auth=new ArrayList<String>();
  server.createContext("/api/v1/projects/"+id+"/ai-library/query",exchange->{try{
   bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
   byte[] result=json.writeValueAsBytes(new LibrarySnapshot(id,8L,provider,new LibrarySnapshot.Categories(LibrarySnapshot.Categories.unavailable().skills(),new LibrarySnapshot.Category(LibrarySnapshot.Status.UNSUPPORTED,List.of(),List.of()),new LibrarySnapshot.Category(LibrarySnapshot.Status.UNSUPPORTED,List.of(),List.of()),new LibrarySnapshot.Category(LibrarySnapshot.Status.AVAILABLE,List.of(new LibrarySnapshot.Item("native-tool","echo","Native tool","MCP · fixture")),List.of(LibrarySnapshot.Diagnostic.MCP_TOOLS_ONLY)))));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);
  }finally{exchange.close();}});server.start();
  try{var p=new ForgeAgentClientProperties();p.setBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
   try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
    context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("runtime",Map.of(legacy?"forge.remote-access.service-secret-file":"FORGE_CODEX_SERVICE_TOKEN_FILE",secret.toString())));
    context.registerBean(ForgeAgentClientProperties.class,()->p);context.registerBean(ObjectMapper.class,()->json);context.registerBean(ForgeAgentClient.class,()->mock(ForgeAgentClient.class));
    context.register(AiLibraryClientAdapter.class);context.refresh();
    var snapshot=context.getBean(AiLibraryClientAdapter.class).query(id,provider);assertEquals(8,snapshot.profileRevision());assertEquals(LibrarySnapshot.Diagnostic.MCP_TOOLS_ONLY,snapshot.categories().tools().diagnostics().getFirst());assertEquals("native-tool",snapshot.categories().tools().items().getFirst().id());assertEquals("selected",snapshot.provider().modelId());assertEquals(List.of("Bearer "+token),auth);assertEquals("codex",json.readTree(bodies.getFirst()).path("provider").path("providerId").asText());assertFalse(bodies.getFirst().contains("cwd"));}}
  finally{server.stop(0);}
 }
 @Test void missingServiceIdentityFailsClosedButStillValidatesProject(){var p=new ForgeAgentClientProperties();p.setBaseUrl(URI.create("http://127.0.0.1:1"));var projects=mock(ForgeAgentClient.class);when(projects.listProjects()).thenReturn(List.of(new AgentProject(id,"p",null,null)));var adapter=new AiLibraryClientAdapter(p,json,projects,"");assertEquals(LibrarySnapshot.Status.UNAVAILABLE,adapter.query(id,provider).categories().skills().status());assertThrows(com.sitionix.forgeai.domain.exception.AgentClientException.class,()->adapter.query(UUID.randomUUID(),provider));}
}
