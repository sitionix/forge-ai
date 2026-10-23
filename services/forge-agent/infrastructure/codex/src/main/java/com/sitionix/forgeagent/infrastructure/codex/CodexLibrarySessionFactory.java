package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.domain.port.library.*;
import org.springframework.stereotype.Component;

@Component
final class CodexLibrarySessionFactory implements LibraryProviderSessionFactory {
 private final CodexAppServerClient client;
 private final java.nio.file.Path globalHome;
 CodexLibrarySessionFactory(CodexAppServerClient client){this(client, "");}
 @org.springframework.beans.factory.annotation.Autowired
 CodexLibrarySessionFactory(CodexAppServerClient client,@org.springframework.beans.factory.annotation.Value("${forge.codex.library.global-home:}") String home){this.client=client;this.globalHome=home.isBlank()?null:java.nio.file.Path.of(home);}
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
  @Override public AgentInventoryPort agents(){return new CodexProjectAgentInventory(globalHome);}
  @Override public FlowInventoryPort flows(){return context->{var reason=initialize(context);return reason==null?InventoryResult.unsupported():InventoryResult.unavailable(reason);};}
  @Override public ToolInventoryPort tools(){return context->{var reason=initialize(context);return reason==null?new CodexLibraryToolInventory(runtime).discover(context):InventoryResult.unavailable(reason);};}
  @Override public void close(){if(runtime!=null)runtime.close();}
 }
}
