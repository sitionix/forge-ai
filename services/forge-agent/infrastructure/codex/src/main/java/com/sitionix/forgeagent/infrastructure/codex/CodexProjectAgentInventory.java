package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.domain.port.library.AgentInventoryPort;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Reads explicitly enabled global and trusted project definitions without executing them. */
final class CodexProjectAgentInventory implements AgentInventoryPort {
 private final Path globalHome;
 CodexProjectAgentInventory(){this(null);}
 private final CodexAgentDefinitionReader reader;
 CodexProjectAgentInventory(Path globalHome){this(globalHome,new CodexAgentDefinitionReader(globalHome));}
 CodexProjectAgentInventory(Path globalHome,CodexAgentDefinitionReader reader){this.globalHome=globalHome;this.reader=reader;}
 @Override public InventoryResult<AgentDescriptor> discover(LibraryContext context) {
  try {
   var response=reader.query(context,null,false);
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
    if(!reader.ownsAgentPath(file,context))throw new IOException();
    String id=CodexAgentDefinitionReader.reference(identity);
    items.add(new AgentDescriptor(id,CodexLibraryMetadataMapper.safeText(item.path("name").asText()),CodexLibraryMetadataMapper.safeText(item.path("description").asText()),new LibraryProvenance(globalHome==null?"Project Codex agent":"Codex agent",identity)));
   }
   return new InventoryResult<>(diagnostics.size()>1?InventoryStatus.FAILED:items.isEmpty()?InventoryStatus.EMPTY:InventoryStatus.AVAILABLE,items,List.copyOf(diagnostics));
  } catch(Exception unavailable) {return InventoryResult.unavailable(InventoryDiagnostic.RUNTIME_UNAVAILABLE);}
 }
}
