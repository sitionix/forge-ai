package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.domain.port.library.ToolInventoryPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Native MCP catalog only. Never creates a thread, issues grants, or calls a tool. */
final class CodexLibraryToolInventory implements ToolInventoryPort {
 private static final int MAX_PAGES=20;
 private static final int MAX_TOOLS=10000;
 private final CodexAppServerClient.CodexInventorySession runtime;
 CodexLibraryToolInventory(CodexAppServerClient.CodexInventorySession runtime){this.runtime=runtime;}
 @Override public InventoryResult<ToolDescriptor> discover(LibraryContext context) {
  var items=new LinkedHashMap<String,ToolDescriptor>();
  var diagnostics=new LinkedHashSet<InventoryDiagnostic>();diagnostics.add(InventoryDiagnostic.MCP_TOOLS_ONLY);
  var cursors=new HashSet<String>();var servers=new HashSet<String>();String cursor=null;int tools=0;
  try {
   for(int pageNumber=0;pageNumber<MAX_PAGES;pageNumber++) {
    final JsonNode page;
    try {page=runtime.listMcpServerStatus(cursor);}
    catch(CodexRemoteException failure) {
     if(pageNumber==0 && "-32601".equals(failure.code()))return InventoryResult.unsupported();
     throw failure;
    }
    if(page==null || !page.path("data").isArray() || page.path("data").size()>100)throw invalid();
    for(var server:page.path("data")) {
     try {
     String name=required(server,"name");String plugin=optional(server,"pluginId");
     if(plugin.length()>512)throw invalid();
     String serverIdentity=name+"\u0000"+plugin;
     if(!servers.add(serverIdentity)) {diagnostics.add(InventoryDiagnostic.INCOMPLETE);continue;}
     if(!server.path("tools").isObject()) {diagnostics.add(InventoryDiagnostic.DISCOVERY_FAILED);continue;}
     if(!server.path("toolsError").isMissingNode() && !server.path("toolsError").isNull())diagnostics.add(InventoryDiagnostic.DISCOVERY_FAILED);
     String state=optional(server,"runtimeStatus");
     if("disabled".equals(state))continue;
     if(Set.of("failed","cancelled","authenticationRequired").contains(state))diagnostics.add(InventoryDiagnostic.DISCOVERY_FAILED);
     var fields=server.path("tools").fields();
     while(fields.hasNext()) {
      if(++tools>MAX_TOOLS) {diagnostics.add(InventoryDiagnostic.LIMIT_EXCEEDED);return result(items,diagnostics);}
      var field=fields.next();
      try {
       String nativeKey=field.getKey();if(nativeKey.isBlank() || nativeKey.length()>512)throw invalid();
       var tool=field.getValue();String toolName=required(tool,"name");String description=optional(tool,"description");
       String identity=serverIdentity+"\u0000"+nativeKey;
       String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("codex\u0000mcp-tools\u0000"+identity).getBytes(StandardCharsets.UTF_8)));
       items.putIfAbsent(id,new ToolDescriptor(id,CodexLibraryMetadataMapper.safeText(toolName),CodexLibraryMetadataMapper.safeText(description),
         new LibraryProvenance("MCP · "+CodexLibraryMetadataMapper.safeText(name),identity)));
      } catch(RuntimeException malformed) {diagnostics.add(InventoryDiagnostic.DISCOVERY_FAILED);}
     }
     } catch(RuntimeException malformedServer) {diagnostics.add(InventoryDiagnostic.DISCOVERY_FAILED);}
    }
    var next=page.path("nextCursor");
    if(next.isMissingNode() || next.isNull())return result(items,diagnostics);
    if(!next.isTextual() || next.asText().isBlank() || next.asText().length()>4096 || !cursors.add(next.asText())) {
     diagnostics.add(InventoryDiagnostic.INCOMPLETE);return result(items,diagnostics);
    }
    cursor=next.asText();
   }
   diagnostics.add(InventoryDiagnostic.LIMIT_EXCEEDED);
  } catch(Exception failed) {diagnostics.add(InventoryDiagnostic.DISCOVERY_FAILED);}
  return result(items,diagnostics);
 }
 private InventoryResult<ToolDescriptor> result(Map<String,ToolDescriptor> items,Set<InventoryDiagnostic> diagnostics) {
  boolean failed=diagnostics.stream().anyMatch(d->d!=InventoryDiagnostic.MCP_TOOLS_ONLY);
  return new InventoryResult<>(failed?InventoryStatus.FAILED:items.isEmpty()?InventoryStatus.EMPTY:InventoryStatus.AVAILABLE,List.copyOf(items.values()),List.copyOf(diagnostics));
 }
 private String required(JsonNode node,String key) {
  var value=node.path(key);if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>512)throw invalid();return value.asText();
 }
 private String optional(JsonNode node,String key) {
  var value=node.path(key);if(value.isNull() || value.isMissingNode())return "";
  if(!value.isTextual() || value.asText().length()>16000)throw invalid();return value.asText();
 }
 private CodexTransportException invalid(){return new CodexTransportException("Invalid native tool inventory");}
}
