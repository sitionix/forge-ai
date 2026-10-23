package com.sitionix.forgeagent.infrastructure.codex;
import com.sitionix.forgeagent.application.definition.AgentDefinitionResolver;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
class CodexNativeAgentDefinitionAdapterTest {
 @TempDir Path project;
 private Path agent()throws Exception {Path dir=Files.createDirectories(project.resolve(".codex/agents"));return Files.writeString(dir.resolve("review.toml"),"name = 'Reviewer'\ndescription = 'Reviews changes'\ndeveloper_instructions = '''Keep complete instructions\nand inspect carefully.'''\nmodel = 'native-model'\nmodel_reasoning_effort = 'high'\n");}
 private String id(){return new CodexProjectAgentInventory().discover(new LibraryContext(UUID.randomUUID(),null,project,List.of(project))).items().getFirst().id();}
 @Test void pinsOverrideInstructionsIncludingCreationDeletionAndSymlinks()throws Exception {
  var adapter=new CodexNativeAgentDefinitionAdapter("");var space=new ExecutionWorkspace(project,List.of(project));
  var initial=adapter.resources(space);Path override=project.resolve("AGENTS.override.md");Files.writeString(override,"original");
  var original=adapter.resources(space);assertNotEquals(initial,original);Files.writeString(override,"changed");assertNotEquals(original,adapter.resources(space));
  Files.delete(override);assertEquals(initial,adapter.resources(space));Files.createSymbolicLink(override,project.resolve("outside"));assertThrows(RuntimeException.class,()->adapter.resources(space));
 }
 @Test void completeRootDefinitionUsesOwnModelAndSecureVersionedResources()throws Exception {
  agent();var adapter=new CodexNativeAgentDefinitionAdapter("");var resolver=new AgentDefinitionResolver(mock(AgentDefinitionRepository.class),adapter);
  var workspace=new ExecutionWorkspace(project,List.of(project));var snapshot=resolver.resolve(UUID.randomUUID(),"NATIVE_CODEX",id(),workspace,new AgentModelSelection("codex","global-model",null));
  assertEquals("native-model",snapshot.model().modelId());assertEquals("high",snapshot.model().effortId());assertTrue(snapshot.instructions().contains("and inspect carefully"));
  assertDoesNotThrow(()->resolver.revalidate(snapshot,workspace));
  Files.writeString(project.resolve(".codex/agents/review.toml"),"name='Changed'\ndescription='Changed'\ndeveloper_instructions='Changed'\n");
  assertThrows(RuntimeException.class,()->resolver.revalidate(snapshot,workspace));assertEquals("native-model",snapshot.model().modelId());
 }
 @Test void rejectsUnknownCapabilityAndSymlinkReplacement()throws Exception {
  Path file=agent();String reference=id();Files.writeString(file,"sandbox_mode='danger-full-access'\n",StandardOpenOption.APPEND);
  var adapter=new CodexNativeAgentDefinitionAdapter("");var space=new ExecutionWorkspace(project,List.of(project));
  assertThrows(RuntimeException.class,()->adapter.resolve(UUID.randomUUID(),reference,space,null));
  Files.delete(file);Path foreign=Files.writeString(project.resolve("outside.toml"),"name='Outside'\ndescription='Outside'\ndeveloper_instructions='Outside'\n");Files.createSymbolicLink(file,foreign);
  assertThrows(RuntimeException.class,()->adapter.resolve(UUID.randomUUID(),reference,space,null));
 }
 @Test void preservesSkillVersionAndRejectsRevocationOrSymlinkEscape()throws Exception {
  agent();Path dir=Files.createDirectories(project.resolve(".agents/skills/proof"));Path skill=Files.writeString(dir.resolve("SKILL.md"),"---\nname: proof\ndescription: Proof\n---\nInitial semantics\n");
  var adapter=new CodexNativeAgentDefinitionAdapter("");var resolver=new AgentDefinitionResolver(mock(AgentDefinitionRepository.class),adapter);var space=new ExecutionWorkspace(project,List.of(project));
  var snapshot=resolver.resolve(UUID.randomUUID(),"NATIVE_CODEX",id(),space,null);assertDoesNotThrow(()->resolver.revalidate(snapshot,space));
  Files.writeString(skill,"Changed semantics");assertThrows(RuntimeException.class,()->resolver.revalidate(snapshot,space));
  Files.delete(skill);Files.createSymbolicLink(skill,project.resolve(".codex/agents/review.toml"));assertThrows(RuntimeException.class,()->adapter.resources(space));
 }
}
