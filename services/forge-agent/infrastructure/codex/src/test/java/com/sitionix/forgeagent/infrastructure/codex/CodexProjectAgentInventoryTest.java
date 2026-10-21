package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.model.library.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CodexProjectAgentInventoryTest {
 @TempDir Path root;
 LibraryContext context(Path... roots){return new LibraryContext(UUID.randomUUID(),new LibraryProviderContext(1,"codex","m",null,null,null),root,List.of(roots));}
 @Test void readsOnlyNativeAgentMetadataAndPreservesDistinctFilesAndProjects() throws Exception {
  var a=root.resolve("a");var b=root.resolve("b");Files.createDirectories(a);Files.createDirectories(b);
  agent(a,"one","same","Review /private/file token=secret");agent(a,"two","same","Second role");agent(b,"other","only-b","Other project");
  var inventory=new CodexProjectAgentInventory();var first=inventory.discover(context(a));var second=inventory.discover(context(b));
  assertEquals(InventoryStatus.AVAILABLE,first.status());assertEquals(2,first.items().size());assertNotEquals(first.items().get(0).id(),first.items().get(1).id());
  assertFalse(first.items().getFirst().description().contains("/private"));assertFalse(first.items().getFirst().description().contains("secret"));
  assertEquals(List.of("only-b"),second.items().stream().map(AgentDescriptor::name).toList());
  assertEquals(first.items().getFirst().id(),inventory.discover(context(a)).items().getFirst().id());
 }
 @Test void emptyAndMalformedNativeFilesAreDifferentAndKeepValidEntries() throws Exception {
  var inventory=new CodexProjectAgentInventory();assertEquals(InventoryStatus.EMPTY,inventory.discover(context(root)).status());
  agent(root,"valid","valid","Good role");Files.writeString(root.resolve(".codex/agents/broken.toml"),"name = ");
  Files.writeString(root.resolve(".codex/agents/notes.md"),"Not an agent definition");
  var result=inventory.discover(context(root));assertEquals(InventoryStatus.FAILED,result.status());assertEquals(1,result.items().size());
 }
 @Test void refusesSymlinkAndHardlinkEscapesWithoutReadingThem() throws Exception {
  var external=root.resolve("external");Files.createDirectories(external);agent(external,"outside","outside","Must stay outside project");
  var project=root.resolve("project");Files.createDirectories(project.resolve(".codex/agents"));
  var file=external.resolve(".codex/agents/outside.toml");
  Files.createSymbolicLink(project.resolve(".codex/agents/link.toml"),file);Files.createLink(project.resolve(".codex/agents/hard.toml"),file);
  var result=new CodexProjectAgentInventory().discover(context(project));assertEquals(InventoryStatus.FAILED,result.status());assertTrue(result.items().isEmpty());
  var linked=root.resolve("linked");Files.createDirectories(linked);Files.createSymbolicLink(linked.resolve(".codex"),external.resolve(".codex"));
  assertEquals(InventoryStatus.FAILED,new CodexProjectAgentInventory().discover(context(linked)).status());
 }
 @Test void neverExecutesAgentInstructionsAndBoundsFileSize() throws Exception {
  agent(root,"safe","safe","Metadata only");var directory=root.resolve(".codex/agents");
  Files.writeString(directory.resolve("too-large.toml"),"x".repeat(65537));
  Files.writeString(directory.resolve("unsafe.toml"),"name = 'script'\ndescription = 'Instructions are data'\ndeveloper_instructions = 'touch "+root.resolve("executed")+"'\n");
  var result=new CodexProjectAgentInventory().discover(context(root));assertEquals(InventoryStatus.FAILED,result.status());assertEquals(2,result.items().size());assertFalse(Files.exists(root.resolve("executed")));
  assertTrue(result.diagnostics().contains(InventoryDiagnostic.LIMIT_EXCEEDED));
 }
 @Test void malformedDeepTomlAndNamedPipesDoNotHideLaterFilesOrRepositories() throws Exception {
  var first=root.resolve("first");var second=root.resolve("second");
  agent(first,"z-good","first","Valid first repository");agent(second,"good","second","Valid second repository");
  var directory=first.resolve(".codex/agents");
  Files.writeString(directory.resolve("a-deep.toml"),"x = "+"[".repeat(2000)+"0"+"]".repeat(2000));
  var fifo=new ProcessBuilder("/usr/bin/mkfifo",directory.resolve("b-pipe.toml").toString()).start();assertEquals(0,fifo.waitFor());
  var result=assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->new CodexProjectAgentInventory().discover(context(first,second)));
  assertEquals(InventoryStatus.FAILED,result.status());assertEquals(List.of("first","second"),result.items().stream().map(AgentDescriptor::name).toList());
  assertTrue(result.diagnostics().contains(InventoryDiagnostic.UNSAFE_RESOURCE));assertTrue(result.diagnostics().contains(InventoryDiagnostic.DISCOVERY_FAILED));
 }
 private void agent(Path project,String file,String name,String description) throws Exception {
  var directory=project.resolve(".codex/agents");Files.createDirectories(directory);
  Files.writeString(directory.resolve(file+".toml"),"name = '"+name+"'\ndescription = '"+description+"'\ndeveloper_instructions = '''\nReview only. Do not execute anything.\n'''\n");
 }
}
