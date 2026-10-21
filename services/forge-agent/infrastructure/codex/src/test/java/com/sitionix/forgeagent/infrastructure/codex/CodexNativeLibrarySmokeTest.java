package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.library.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real installed binary, isolated HOME, no inference. Not proof of systemd/UID deployment. */
@EnabledIfSystemProperty(named="forge.codex.library-smoke.binary",matches=".+")
class CodexNativeLibrarySmokeTest {
 @TempDir Path root;
 @Test void nativeCanonicalPathsPreserveProjectIsolationAndDistinctGlobalNames() throws Exception {
  Path home=root.resolve("home");Path codex=home.resolve(".codex");Files.createDirectories(codex);
  Path a=root.resolve("a"),b=root.resolve("b");Files.createDirectories(a);Files.createDirectories(b);
  skill(codex.resolve("skills/shared"),"same","global");skill(home.resolve(".agents/skills/alternate"),"alternate","alternate global");
  skill(a.resolve(".agents/skills/local"),"same","project A");skill(b.resolve(".agents/skills/local"),"only-b","project B");
  var foreign=root.resolve("foreign");skill(foreign,"foreign-directory","must not escape");
  Files.createSymbolicLink(codex.resolve("skills/directory-link"),foreign);
  var foreignFile=root.resolve("foreign-file");skill(foreignFile,"foreign-file","must not escape");
  Files.createDirectories(codex.resolve("skills/file-link"));Files.createSymbolicLink(codex.resolve("skills/file-link/SKILL.md"),foreignFile.resolve("SKILL.md"));
  var indirect=root.resolve("indirect");Files.createDirectories(indirect);skill(indirect.resolve("nested"),"indirect-foreign","must not escape");
  Files.createSymbolicLink(codex.resolve("skills/intermediate-link"),indirect);
  var props=new CodexAppServerProperties();
  CodexAppServerProcessStarter starter=cwd->{try {
   var builder=new ProcessBuilder(System.getProperty("forge.codex.library-smoke.binary"),"app-server","--stdio").directory(cwd.toFile());
   builder.environment().clear();builder.environment().putAll(Map.of("HOME",home.toString(),"CODEX_HOME",codex.toString(),"PATH","/usr/bin:/bin","LANG","C.UTF-8"));
   return new StartedCodexAppServer(builder.start(),List.of("native-readonly-smoke"),Instant.now());
  }catch(java.io.IOException failure){throw new IllegalStateException(failure);}};
  var json=new ObjectMapper();var client=new CodexAppServerClient(json,starter,props,new CodexRuntimeWorkspace(props),null,new ForgeAuthorizationFixture(false).gate);
  try(var runtime=client.openInventory(a)) {
   var nativeResult=runtime.listSkills(List.of(a,b));
   var aResponse=json.createObjectNode();aResponse.putArray("data").add(nativeResult.path("data").get(0));
   var bResponse=json.createObjectNode();bResponse.putArray("data").add(nativeResult.path("data").get(1));
   var provider=new LibraryProviderContext(1,"codex","fixture",null,null,null);
   var mapper=new CodexLibraryMetadataMapper();
   var ai=mapper.skills(aResponse,new LibraryContext(UUID.randomUUID(),provider,a,List.of(a)),runtime.codexHome());
   var bi=mapper.skills(bResponse,new LibraryContext(UUID.randomUUID(),provider,b,List.of(b)),runtime.codexHome());
   assertEquals(2,ai.items().stream().filter(s->s.name().equals("same")).count());
   assertEquals(1,bi.items().stream().filter(s->s.name().equals("same")).count());assertTrue(bi.items().stream().anyMatch(s->s.name().equals("only-b")));
   assertTrue(ai.items().stream().anyMatch(s->s.name().equals("alternate")));
   assertFalse(ai.items().stream().anyMatch(s->s.name().contains("foreign")));
   assertFalse(bi.items().stream().anyMatch(s->s.description().equals("project A")));
   var nativeNames=new HashSet<String>();
   for(var entry:nativeResult.path("data"))for(var skill:entry.path("skills"))nativeNames.add(skill.path("name").asText());
   assertTrue(nativeNames.containsAll(Set.of("foreign-directory","indirect-foreign")), "Native symlink probe names: "+nativeNames);
   assertFalse(nativeNames.contains("foreign-file"), "Native runtime ignores a symlink SKILL.md file");
   // Returned directory/intermediate symlinks use canonical target paths.
   for(var entry:nativeResult.path("data"))for(var skill:entry.path("skills")) {
    assertEquals(Path.of(skill.path("path").asText()).toRealPath().toString(),skill.path("path").asText());
   }
  }
 }
 private void skill(Path path,String name,String description) throws Exception {Files.createDirectories(path);Files.writeString(path.resolve("SKILL.md"),"---\nname: "+name+"\ndescription: "+description+"\n---\nRead-only fixture.\n");}
}
