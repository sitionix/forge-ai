package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.domain.port.library.*;
import org.springframework.stereotype.Component;

@Component
final class CodexLibrarySessionFactory implements LibraryProviderSessionFactory {
 private final CodexAppServerClient client;
 CodexLibrarySessionFactory(CodexAppServerClient client){this.client=client;}
 @Override public boolean supports(String providerId){return "codex".equals(providerId);}
 @Override public LibraryProviderSession open(LibraryContext context){return new Session();}
 private final class Session implements LibraryProviderSession {
  private CodexAppServerClient.CodexInventorySession runtime;
  private boolean attempted;
  private InventoryDiagnostic unavailable;
  private InventoryDiagnostic initialize(LibraryContext context) {
   if(!attempted) {
    attempted=true;
    try {runtime=client.openInventory(context.cwd());}
    catch(CodexLibraryCapabilities.UnverifiedProtocol unverified){unavailable=InventoryDiagnostic.RUNTIME_PROTOCOL_UNVERIFIED;}
    catch(RuntimeException failure){unavailable=InventoryDiagnostic.RUNTIME_UNAVAILABLE;}
   }
   return unavailable;
  }
  @Override public SkillInventoryPort skills(){return context->{
   var reason=initialize(context);if(reason!=null)return InventoryResult.unavailable(reason);
   try {return new CodexLibraryMetadataMapper().skills(runtime.listSkills(context.workspaceRoots()),context,runtime.codexHome());}
   catch(CodexRemoteException remote){return "-32601".equals(remote.code())?InventoryResult.unsupported():InventoryResult.failed();}
   catch(RuntimeException failed){return InventoryResult.failed();}
  };}
  @Override public AgentInventoryPort agents(){return context->{var reason=initialize(context);return reason==null?InventoryResult.unsupported():InventoryResult.unavailable(reason);};}
  @Override public FlowInventoryPort flows(){return context->{var reason=initialize(context);return reason==null?InventoryResult.unsupported():InventoryResult.unavailable(reason);};}
  @Override public ToolInventoryPort tools(){return context->{var reason=initialize(context);return InventoryResult.unavailable(reason==null?InventoryDiagnostic.MCP_EXECUTION_CONTEXT_REQUIRED:reason);};}
  @Override public void close(){if(runtime!=null)runtime.close();}
 }
}
