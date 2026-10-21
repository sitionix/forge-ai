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

class CodexLibraryToolsInventoryTest {
 @TempDir Path root;
 final ObjectMapper json=new ObjectMapper();
 LibraryContext context(){return new LibraryContext(UUID.randomUUID(),new LibraryProviderContext(1,"codex","m",null,null,null),root,List.of(root));}
 @Test void paginatesNativeMcpToolsWithoutThreadsOrExecutionAndKeepsServerIdentity() throws Exception {
  var result=discover(List.of(
   "\"result\":{\"data\":["+server("a",false)+"],\"nextCursor\":\"second\"}",
   "\"result\":{\"data\":["+server("b",false)+"],\"nextCursor\":null}"));
  assertEquals(InventoryStatus.AVAILABLE,result.status());assertEquals(2,result.items().size());
  assertNotEquals(result.items().get(0).id(),result.items().get(1).id());
  assertEquals("echo",result.items().getFirst().name());
  assertFalse(result.items().getFirst().description().contains("/private"));
  assertFalse(result.items().getFirst().description().contains("credential"));
  assertEquals("MCP · a",result.items().getFirst().provenance().source());
  assertTrue(result.diagnostics().stream().anyMatch(d->d.name().equals("MCP_TOOLS_ONLY")));
 }
 @Test void supportedEmptyIsDifferentFromMissingOperation() throws Exception {
  assertEquals(InventoryStatus.EMPTY,discover(List.of("\"result\":{\"data\":[],\"nextCursor\":null}")).status());
  assertEquals(InventoryStatus.UNSUPPORTED,discover(List.of("\"error\":{\"code\":-32601,\"message\":\"missing\"}")).status());
 }
 @Test void serverAndLaterPageFailuresPreserveSuccessfulTools() throws Exception {
  var partial=discover(List.of("\"result\":{\"data\":["+server("good",false)+","+server("broken",true)+"]}"));
  assertEquals(InventoryStatus.FAILED,partial.status());assertEquals(1,partial.items().size());
  var pageFailure=discover(List.of("\"result\":{\"data\":["+server("good",false)+"],\"nextCursor\":\"second\"}","\"error\":{\"code\":-1,\"message\":\"private error\"}"));
  assertEquals(InventoryStatus.FAILED,pageFailure.status());assertEquals(1,pageFailure.items().size());
 }
 @Test void repeatedCursorStopsTraversalWithPartialResult() throws Exception {
  var result=discover(List.of("\"result\":{\"data\":["+server("a",false)+"],\"nextCursor\":\"same\"}","\"result\":{\"data\":["+server("b",false)+"],\"nextCursor\":\"same\"}"));
  assertEquals(InventoryStatus.FAILED,result.status());assertEquals(2,result.items().size());
 }
 @Test void inventoryEntryAndPageLimitsAreExplicitAndBounded() throws Exception {
  var tools=json.createObjectNode();
  for(int n=0;n<10001;n++)tools.putObject("tool"+n).put("name","tool"+n).putObject("inputSchema").put("type","object");
  var page=json.createObjectNode();page.putArray("data").addObject().put("name","many").set("tools",tools);
  var result=discover(List.of("\"result\":"+json.writeValueAsString(page)));
  assertEquals(10000,result.items().size());assertEquals(InventoryStatus.FAILED,result.status());assertTrue(result.diagnostics().contains(InventoryDiagnostic.LIMIT_EXCEEDED));
  var pages=new ArrayList<String>();
  for(int n=0;n<20;n++)pages.add("\"result\":{\"data\":[],\"nextCursor\":\"page"+n+"\"}");
  var bounded=discover(pages);assertEquals(InventoryStatus.FAILED,bounded.status());assertTrue(bounded.diagnostics().contains(InventoryDiagnostic.LIMIT_EXCEEDED));
 }
 @Test void malformedServersDoNotHideFollowingUsableTools() throws Exception {
  var result=discover(List.of("\"result\":{\"data\":[{},"+server("first",false)+",{\"name\":\"broken\",\"pluginId\":1,\"tools\":{}},"+server("last",false)+"]}"));
  assertEquals(InventoryStatus.FAILED,result.status());assertEquals(2,result.items().size());
  assertEquals(List.of("MCP · first","MCP · last"),result.items().stream().map(t->t.provenance().source()).toList());
 }
 private String server(String name,boolean failed) {return "{\"name\":\""+name+"\",\"authStatus\":\"unsupported\",\"runtimeStatus\":null,\"toolsError\":"+(failed?"\"sensitive failure\"":"null")+",\"tools\":{"+(failed?"":"\"echo\":{\"name\":\"echo\",\"description\":\"Read /private/file token=credential\",\"inputSchema\":{\"sensitive\":\"must not expose\"}}")+"}}";}
 private InventoryResult<ToolDescriptor> discover(List<String> replies) throws Exception {
  var process=new FakeCodexProcess();var props=new CodexAppServerProperties();props.setRequestTimeout(Duration.ofSeconds(2));props.setGracefulTerminateTimeout(Duration.ofMillis(10));props.setForceKillTimeout(Duration.ofMillis(100));
  var client=new CodexAppServerClient(json,cwd->{assertEquals(root,cwd);return new StartedCodexAppServer(process,List.of("fixture"),Instant.now());},props,new CodexRuntimeWorkspace(props),null,new ForgeAuthorizationFixture(false).gate);
  var context=context();var future=CompletableFuture.supplyAsync(()->{try(var session=new CodexLibrarySessionFactory(client).open(context)){return session.tools().discover(context);}});
  var init=json.readTree(process.readRequest());assertEquals("initialize",init.path("method").asText());
  process.writeStdout("{\"id\":\""+init.path("id").asText()+"\",\"result\":{\"userAgent\":\"codex/0.163.0\",\"codexHome\":\""+root+"/runtime/.codex\"}}");
  assertEquals("initialized",json.readTree(process.readRequest()).path("method").asText());
  for(int page=0;page<replies.size();page++) {
   var frame=process.readRequest();assertNotNull(frame,"Native Tools discovery must issue its read-only inventory request");var request=json.readTree(frame);assertEquals("mcpServerStatus/list",request.path("method").asText());
   assertEquals(100,request.path("params").path("limit").asInt());assertEquals("toolsAndAuthOnly",request.path("params").path("detail").asText());
   if(page==0)assertFalse(request.path("params").has("cursor"));
   else assertEquals(json.readTree("{"+replies.get(page-1)+"}").path("result").path("nextCursor").asText(),request.path("params").path("cursor").asText());
   assertFalse(request.path("params").has("threadId"));assertFalse(request.path("params").has("config"));
   process.writeStdout("{\"id\":\""+request.path("id").asText()+"\","+replies.get(page)+"}");
  }
  var result=future.get(3,TimeUnit.SECONDS);assertFalse(process.isAlive());assertEquals(0,process.pendingClientRequestBytes());
  assertThrows(RuntimeException.class,()->client.request("mcpServerStatus/list",json.createObjectNode()));
  return result;
 }
}
