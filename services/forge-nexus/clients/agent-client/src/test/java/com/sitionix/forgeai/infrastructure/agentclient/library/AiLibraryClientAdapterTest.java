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
 @Test void actualHttpComposesTypedRequestAndAuthenticatesWithoutBrowserHeaders() throws Exception {
  var secret=directory.resolve("secret");Files.writeString(secret,"a".repeat(43));Files.setPosixFilePermissions(secret,PosixFilePermissions.fromString("rw-------"));
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var bodies=new ArrayList<String>();var auth=new ArrayList<String>();
  server.createContext("/api/v1/projects/"+id+"/ai-library/query",exchange->{try{
   bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
   byte[] result=json.writeValueAsBytes(new LibrarySnapshot(id,8L,provider,LibrarySnapshot.Categories.unavailable()));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);
  }finally{exchange.close();}});server.start();
  try{var p=new ForgeAgentClientProperties();p.setBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));var adapter=new AiLibraryClientAdapter(p,json,mock(ForgeAgentClient.class),secret.toString());var snapshot=adapter.query(id,provider);assertEquals(8,snapshot.profileRevision());assertEquals("selected",snapshot.provider().modelId());assertEquals(List.of("Bearer "+"a".repeat(43)),auth);assertEquals("codex",json.readTree(bodies.getFirst()).path("provider").path("providerId").asText());assertFalse(bodies.getFirst().contains("cwd"));}
  finally{server.stop(0);}
 }
 @Test void missingServiceIdentityFailsClosedButStillValidatesProject(){var p=new ForgeAgentClientProperties();p.setBaseUrl(URI.create("http://127.0.0.1:1"));var projects=mock(ForgeAgentClient.class);when(projects.listProjects()).thenReturn(List.of(new AgentProject(id,"p",null,null)));var adapter=new AiLibraryClientAdapter(p,json,projects,"");assertEquals(LibrarySnapshot.Status.UNAVAILABLE,adapter.query(id,provider).categories().skills().status());assertThrows(com.sitionix.forgeai.domain.exception.AgentClientException.class,()->adapter.query(UUID.randomUUID(),provider));}
}
