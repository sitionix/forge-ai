package com.sitionix.forgeagent.infrastructure.codex;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.library.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CodexLibraryInventoryTest {
 @TempDir Path root;
 final ObjectMapper json=new ObjectMapper();
 LibraryContext context(){return new LibraryContext(UUID.randomUUID(),new LibraryProviderContext(1,"codex","m",null,null,null),root,List.of(root));}
 @Test void preservesDistinctSameNameResourcesAndPartialErrorsWithoutLeakingPaths() throws Exception {
  for(String name:List.of("a","b","c")){Files.createDirectories(root.resolve(name));Files.writeString(root.resolve(name).resolve("SKILL.md"),"fixture");}
  var response=json.readTree("""
   {"data":[{"cwd":"CWD","errors":[{"message":"secret","path":"private"}],"skills":[
   {"name":"same","description":"Read /private/file token=secret","path":"CWD/a/SKILL.md","scope":"repo","enabled":true},
   {"name":"same","description":"other","path":"CWD/b/SKILL.md","scope":"repo","enabled":true},
   {"name":"off","description":"disabled","path":"CWD/c/SKILL.md","scope":"repo","enabled":false}]}]}
   """.replace("CWD",root.toString()));
  var result=new CodexLibraryMetadataMapper().skills(response,context(),root.resolve("home/.codex"));
  assertEquals(InventoryStatus.FAILED,result.status());assertEquals(2,result.items().size());assertNotEquals(result.items().get(0).id(),result.items().get(1).id());
  assertFalse(result.items().getFirst().description().contains("/private"));assertFalse(result.items().getFirst().description().contains("secret"));
 }
 @Test void rejectsForeignCwdAndSymlinkEscape() throws Exception {
  Files.createSymbolicLink(root.resolve("escape"),Path.of("/tmp"));
  var r=json.readTree("{\"data\":[{\"cwd\":\""+root+"\",\"errors\":[],\"skills\":[{\"name\":\"bad\",\"description\":\"bad\",\"path\":\""+root+"/escape/SKILL.md\",\"scope\":\"repo\",\"enabled\":true}]}]}");
  assertEquals(InventoryStatus.FAILED,new CodexLibraryMetadataMapper().skills(r,context(),root.resolve("home/.codex")).status());
  assertThrows(RuntimeException.class,()->new CodexLibraryMetadataMapper().skills(json.readTree("{\"data\":[{\"cwd\":\"/other\",\"errors\":[],\"skills\":[]}]}"),context(),root.resolve("home/.codex")));
 }
 @Test void rejectsGlobalResourcesOutsideRuntimeRootsAndSymlinkEscapes() throws Exception {
  var home=root.resolve("runtime/.codex");var skills=home.resolve("skills");Files.createDirectories(skills.resolve("safe"));Files.writeString(skills.resolve("safe/SKILL.md"),"fixture");
  Files.createSymbolicLink(skills.resolve("escape"),Path.of("/etc"));
  var response=json.readTree("{\"data\":[{\"cwd\":\""+root+"\",\"errors\":[],\"skills\":[{\"name\":\"safe\",\"description\":\"safe\",\"path\":\""+skills+"/safe/SKILL.md\",\"scope\":\"user\",\"enabled\":true},{\"name\":\"foreign\",\"description\":\"foreign\",\"path\":\"/etc/passwd\",\"scope\":\"user\",\"enabled\":true},{\"name\":\"escape\",\"description\":\"escape\",\"path\":\""+skills+"/escape/passwd\",\"scope\":\"system\",\"enabled\":true}]}]}");
  var result=new CodexLibraryMetadataMapper().skills(response,context(),home);assertEquals(1,result.items().size());assertEquals("safe",result.items().getFirst().name());assertEquals(InventoryStatus.FAILED,result.status());
 }
 @Test void nativeInventoryUsesOneOwnedProcessAndNeverStartsExecution() throws Exception {
  var process=new FakeCodexProcess();var props=new CodexAppServerProperties();props.setRequestTimeout(Duration.ofSeconds(2));props.setGracefulTerminateTimeout(Duration.ofMillis(10));props.setForceKillTimeout(Duration.ofMillis(100));
  CodexAppServerProcessStarter starter=cwd->{assertEquals(root,cwd);return new StartedCodexAppServer(process,List.of("fixture"),Instant.now());};
  var client=new CodexAppServerClient(json,starter,props,new CodexRuntimeWorkspace(props),null,new ForgeAuthorizationFixture(false).gate);
  var future=CompletableFuture.supplyAsync(()->{try(var session=client.openInventory(root)){return session.listSkills(List.of(root));}});
  var init=json.readTree(process.readRequest());assertEquals("initialize",init.path("method").asText());
  process.writeStdout("{\"id\":\""+init.path("id").asText()+"\",\"result\":{\"userAgent\":\"codex/0.163.0\",\"codexHome\":\""+root+"/runtime/.codex\"}}");
  assertEquals("initialized",json.readTree(process.readRequest()).path("method").asText());
  var request=json.readTree(process.readRequest());assertEquals("skills/list",request.path("method").asText());assertEquals(root.toString(),request.path("params").path("cwds").get(0).asText());
  process.writeStdout("{\"id\":\""+request.path("id").asText()+"\",\"result\":{\"data\":[]}}");
  assertTrue(future.get(3,TimeUnit.SECONDS).path("data").isArray());assertFalse(process.isAlive());
  assertThrows(RuntimeException.class,()->client.request("skills/list",json.createObjectNode()));
 }
 @Test void malformedDiscoveryIsFailedAndMissingOperationIsUnsupported() throws Exception {
  assertEquals(InventoryStatus.FAILED, discoverWithReply("\"result\":{\"data\":\"invalid\"}").status());
  assertEquals(InventoryStatus.UNSUPPORTED, discoverWithReply("\"error\":{\"code\":-32601,\"message\":\"method missing\"}").status());
  assertEquals(InventoryStatus.EMPTY, discoverWithReply("\"result\":{\"data\":[{\"cwd\":\""+root+"\",\"errors\":[],\"skills\":[]}]}").status());
 }
 private InventoryResult<SkillDescriptor> discoverWithReply(String reply) throws Exception {
  var process=new FakeCodexProcess();var props=new CodexAppServerProperties();props.setRequestTimeout(Duration.ofSeconds(2));props.setGracefulTerminateTimeout(Duration.ofMillis(10));props.setForceKillTimeout(Duration.ofMillis(100));
  CodexAppServerProcessStarter starter=cwd->new StartedCodexAppServer(process,List.of("fixture"),Instant.now());
  var client=new CodexAppServerClient(json,starter,props,new CodexRuntimeWorkspace(props),null,new ForgeAuthorizationFixture(false).gate);
  var future=CompletableFuture.supplyAsync(()->{try(var session=new CodexLibrarySessionFactory(client).open(context())){return session.skills().discover(context());}});
  var init=json.readTree(process.readRequest());process.writeStdout("{\"id\":\""+init.path("id").asText()+"\",\"result\":{\"userAgent\":\"codex/0.163.0\",\"codexHome\":\""+root+"/runtime/.codex\"}}");
  process.readRequest();var request=json.readTree(process.readRequest());process.writeStdout("{\"id\":\""+request.path("id").asText()+"\","+reply+"}");
  var result=future.get(3,TimeUnit.SECONDS);assertFalse(process.isAlive());return result;
 }
 @Test void logoutDuringInventoryInitializationCancelsOwnedProcess() throws Exception {
  var process=new FakeCodexProcess();var props=new CodexAppServerProperties();props.setRequestTimeout(Duration.ofSeconds(2));props.setGracefulTerminateTimeout(Duration.ofMillis(10));props.setForceKillTimeout(Duration.ofMillis(100));
  var auth=new ForgeAuthorizationFixture(true);
  var client=new CodexAppServerClient(json,cwd->new StartedCodexAppServer(process,List.of("fixture"),Instant.now()),props,new CodexRuntimeWorkspace(props),null,auth.gate);
  var future=CompletableFuture.runAsync(()->{try(var session=client.openInventory(root)){session.listSkills(List.of(root));}});
  assertEquals("initialize",json.readTree(process.readRequest()).path("method").asText());auth.service.logout();
  assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));assertFalse(process.isAlive());
 }
 @Test void unverifiedNativeVersionIsUnavailableWithoutDiscovery() throws Exception {
  var process=new FakeCodexProcess();var props=new CodexAppServerProperties();props.setRequestTimeout(Duration.ofSeconds(2));props.setGracefulTerminateTimeout(Duration.ofMillis(10));props.setForceKillTimeout(Duration.ofMillis(100));
  var client=new CodexAppServerClient(json,cwd->new StartedCodexAppServer(process,List.of("fixture"),Instant.now()),props,new CodexRuntimeWorkspace(props),null,new ForgeAuthorizationFixture(false).gate);
  var future=CompletableFuture.supplyAsync(()->{try(var session=new CodexLibrarySessionFactory(client).open(context())){return new LibraryCategories(session.skills().discover(context()),session.agents().discover(context()),session.flows().discover(context()),session.tools().discover(context()));}});
  var init=json.readTree(process.readRequest());process.writeStdout("{\"id\":\""+init.path("id").asText()+"\",\"result\":{\"userAgent\":\"codex/999.0.0\",\"codexHome\":\""+root+"/home/.codex\"}}");
  process.readRequest();var result=future.get(3,TimeUnit.SECONDS);for(var category:List.of(result.skills(),result.flows(),result.tools())) {assertEquals(InventoryStatus.UNAVAILABLE,category.status());assertTrue(category.diagnostics().contains(InventoryDiagnostic.RUNTIME_PROTOCOL_UNVERIFIED));}assertEquals(InventoryStatus.EMPTY,result.agents().status());assertTrue(result.agents().diagnostics().contains(InventoryDiagnostic.PROJECT_AGENT_FILES_ONLY));assertFalse(process.isAlive());assertEquals(0,process.pendingClientRequestBytes());
 }
 @Test void largeProviderResultIsBoundedAndExplicitlyIncomplete() throws Exception {
  var file=root.resolve("SKILL.md");Files.writeString(file,"fixture");
  var data=json.createObjectNode();var entry=data.putArray("data").addObject().put("cwd",root.toString());entry.putArray("errors");var skills=entry.putArray("skills");
  for(int n=0;n<10001;n++)skills.addObject().put("name","same").put("description","fixture").put("path",file.toString()).put("scope","repo").put("enabled",true);
  var result=new CodexLibraryMetadataMapper().skills(data,context(),root.resolve("home/.codex"));assertEquals(InventoryStatus.FAILED,result.status());assertTrue(result.diagnostics().contains(InventoryDiagnostic.LIMIT_EXCEEDED));assertEquals(1,result.items().size());
 }
}
