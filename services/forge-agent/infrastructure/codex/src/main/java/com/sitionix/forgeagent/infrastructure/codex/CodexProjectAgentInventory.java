package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.domain.port.library.AgentInventoryPort;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Reads explicitly enabled global and trusted project definitions without executing them. */
final class CodexProjectAgentInventory implements AgentInventoryPort {
 private final Path globalHome;
 CodexProjectAgentInventory(){this(null);}
 CodexProjectAgentInventory(Path globalHome){this.globalHome=globalHome;}
 private static final int MAX_OUTPUT=1048576;
 private static final long BUDGET_NANOS=TimeUnit.SECONDS.toNanos(2);
 private static final ObjectMapper JSON=new ObjectMapper();
 private boolean allowedAgentPath(Path file,LibraryContext context){return context.workspaceRoots().stream().anyMatch(r->file.getParent().equals(r.resolve(".codex/agents"))) || globalHome!=null && file.getParent().equals(globalHome.resolve(".codex/agents"));}
 @Override public InventoryResult<AgentDescriptor> discover(LibraryContext context) {
  Process process=null;FutureTask<byte[]> reading=null;
  long deadline=System.nanoTime()+BUDGET_NANOS;
  try {
   Path project=context.cwd();
   if(!project.isAbsolute() || !project.normalize().equals(project) || project.getParent()==null || context.workspaceRoots().size()>32
      || context.workspaceRoots().stream().anyMatch(p->!p.isAbsolute() || !p.normalize().equals(p) || !p.startsWith(project)))throw new IOException();
   final String script;
   try(var input=CodexProjectAgentInventory.class.getResourceAsStream("/codex/project_agent_inventory.py")) {
    if(input==null)throw new IOException();script=new String(input.readNBytes(16385),StandardCharsets.UTF_8);if(script.length()>16384)throw new IOException();
   }
   var request=JSON.createObjectNode().put("projectRoot",project.toString());var roots=request.putArray("roots");context.workspaceRoots().forEach(p->roots.add(p.toString()));
   if(globalHome!=null){if(!globalHome.isAbsolute() || !globalHome.normalize().equals(globalHome) || globalHome.getParent()==null)throw new IOException();request.put("globalHome",globalHome.toString());}
   byte[] input=JSON.writeValueAsBytes(request);if(input.length>131072)throw new IOException();
   var builder=new ProcessBuilder("/usr/bin/python3","-I","-c",script).directory(project.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
   builder.environment().clear();builder.environment().put("LANG","C.UTF-8");
   process=builder.start();final Process owned=process;
   reading=new FutureTask<>(()->{
    try(var stdin=owned.getOutputStream()){stdin.write(input);}
    try(var stdout=owned.getInputStream()){return stdout.readNBytes(MAX_OUTPUT+1);}
   });
   Thread.startVirtualThread(reading);
   long remaining=deadline-System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(250);if(remaining<=0)throw new TimeoutException();
   byte[] output=reading.get(remaining,TimeUnit.NANOSECONDS);
   remaining=deadline-System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(250);
   if(output.length>MAX_OUTPUT || remaining<=0 || !process.waitFor(remaining,TimeUnit.NANOSECONDS) || process.exitValue()!=0)throw new IOException();
   var response=JSON.readTree(output);if(!response.path("items").isArray() || !response.path("diagnostics").isArray() || response.path("items").size()>512)throw new IOException();
   var diagnostics=new LinkedHashSet<InventoryDiagnostic>();diagnostics.add(globalHome==null?InventoryDiagnostic.PROJECT_AGENT_FILES_ONLY:InventoryDiagnostic.AGENT_FILES_ONLY);
   for(var value:response.path("diagnostics")) {
    var diagnostic=InventoryDiagnostic.valueOf(value.asText());
    if(!Set.of(InventoryDiagnostic.DISCOVERY_FAILED,InventoryDiagnostic.WORKSPACE_UNAVAILABLE,InventoryDiagnostic.LIMIT_EXCEEDED,InventoryDiagnostic.UNSAFE_RESOURCE).contains(diagnostic))throw new IOException();
    diagnostics.add(diagnostic);
   }
   var items=new ArrayList<AgentDescriptor>();
   for(var item:response.path("items")) {
    if(!item.path("identity").isTextual() || !item.path("name").isTextual() || !item.path("description").isTextual())throw new IOException();
    String identity=item.path("identity").asText();Path file=Path.of(identity);
    if(!file.isAbsolute() || !file.normalize().equals(file) || !allowedAgentPath(file,context) || !file.getFileName().toString().endsWith(".toml"))throw new IOException();
    String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("codex\u0000agent-file\u0000"+identity).getBytes(StandardCharsets.UTF_8)));
    items.add(new AgentDescriptor(id,CodexLibraryMetadataMapper.safeText(item.path("name").asText()),CodexLibraryMetadataMapper.safeText(item.path("description").asText()),new LibraryProvenance(globalHome==null?"Project Codex agent":"Codex agent",identity)));
   }
   return new InventoryResult<>(diagnostics.size()>1?InventoryStatus.FAILED:items.isEmpty()?InventoryStatus.EMPTY:InventoryStatus.AVAILABLE,items,List.copyOf(diagnostics));
  } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return InventoryResult.unavailable(InventoryDiagnostic.RUNTIME_UNAVAILABLE);}
  catch(Exception unavailable) {return InventoryResult.unavailable(InventoryDiagnostic.RUNTIME_UNAVAILABLE);}
  finally {
   if(process!=null && process.isAlive()) {
    process.destroyForcibly();
    try{process.waitFor(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
   }
   if(reading!=null)reading.cancel(true);
  }
 }
}
