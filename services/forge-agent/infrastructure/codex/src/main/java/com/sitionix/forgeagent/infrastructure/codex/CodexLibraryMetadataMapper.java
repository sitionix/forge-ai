package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.domain.model.library.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Allowlisted metadata only. Native resource paths are identity material, never API fields. */
final class CodexLibraryMetadataMapper {
 InventoryResult<SkillDescriptor> skills(JsonNode response,LibraryContext context,Path codexHome) {
  if(response==null || !response.path("data").isArray()) throw invalid();
  var items=new LinkedHashMap<String,SkillDescriptor>();var diagnostics=new LinkedHashSet<InventoryDiagnostic>();
  var seenCwds=new HashSet<Path>();int entries=0;
  for(var entry:response.path("data")) {
   var cwd=path(entry,"cwd");
   if(!context.workspaceRoots().contains(cwd)||!seenCwds.add(cwd)||!entry.path("skills").isArray()||!entry.path("errors").isArray()) throw invalid();
   if(!entry.path("errors").isEmpty()) diagnostics.add(InventoryDiagnostic.INCOMPLETE);
   for(var skill:entry.path("skills")) {
    if(++entries>10000) {diagnostics.add(InventoryDiagnostic.LIMIT_EXCEEDED);break;}
    if(!skill.path("enabled").isBoolean()) throw invalid();
    if(!skill.path("enabled").asBoolean()) continue;
    var nativePath=path(skill,"path");String scope=required(skill,"scope");
    if(!Set.of("repo","user","system","admin").contains(scope)) throw invalid();
    var allowedRoots=scope.equals("repo")?context.workspaceRoots():List.of(codexHome.resolve("skills"),codexHome.resolve(".agents/skills"),codexHome.getParent().resolve(".agents/skills"));
    if(!(scope.equals("repo") ? safeResource(nativePath,allowedRoots) : safeNativeGlobalResource(nativePath,allowedRoots))) {
     diagnostics.add(InventoryDiagnostic.UNSAFE_RESOURCE);continue;
    }
    String nativeIdentity=nativePath+"\u0000"+skill.path("pluginId").asText("");
    String id=digest("codex\u0000skills\u0000"+nativeIdentity);
    items.putIfAbsent(id,new SkillDescriptor(id,safeText(required(skill,"name")),safeText(required(skill,"description")),new LibraryProvenance(scope,nativeIdentity)));
   }
   if(entries>10000)break;
  }
  if(seenCwds.size()!=context.workspaceRoots().size())diagnostics.add(InventoryDiagnostic.INCOMPLETE);
  return diagnostics.isEmpty()?InventoryResult.available(List.copyOf(items.values())):
   new InventoryResult<>(InventoryStatus.FAILED,List.copyOf(items.values()),List.copyOf(diagnostics));
 }
 /** The session accepts only a native version whose skills/list canonicalizes paths.
  * Control UID cannot traverse runtime HOME0700; never relax it for inventory.
  * These are metadata identities, never file read/execution authority.
  */
 private boolean safeNativeGlobalResource(Path path,List<Path> roots) {
  if(roots.stream().noneMatch(path::startsWith))return false;
  for(Path cursor=path;cursor!=null;cursor=cursor.getParent())if(Files.isSymbolicLink(cursor))return false;
  return !Files.isReadable(path) || safeResource(path,roots);
 }
 private boolean safeResource(Path path,List<Path> roots) {
  if(roots.stream().noneMatch(path::startsWith))return false;
  for(Path cursor=path;cursor!=null;cursor=cursor.getParent()) if(Files.isSymbolicLink(cursor))return false;
  try {
   if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return false;
   var canonical=path.toRealPath();
   for(var root:roots) if(path.startsWith(root) && canonical.startsWith(root.toRealPath()))return true;
  }catch(java.io.IOException|SecurityException inaccessible){return false;}
  return false;
 }
 private Path path(JsonNode node,String key) {
  String text=required(node,key);if(text.length()>4096)throw invalid();Path path;
  try {path=Path.of(text);}catch(RuntimeException invalid){throw invalid();}
  if(!path.isAbsolute()||!path.normalize().equals(path))throw invalid();return path;
 }
 private String required(JsonNode node,String key) {var value=node.path(key);if(!value.isTextual())throw invalid();return value.asText();}
 static String safeText(String text) {
  String safe=text.replaceAll("(?i)(?:bearer\\s+|(?:token|password|secret|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+","[redacted]")
   .replaceAll("(?:[A-Za-z]:[\\\\/]|/|~[/\\\\])[^\\s<>]*","[path]")
   .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","");
  return safe.substring(0,Math.min(safe.length(),2000));
 }
 private String digest(String input) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private CodexTransportException invalid(){return new CodexTransportException("Invalid skill inventory response");}
}
