package com.sitionix.forgeai.domain.model.library;
import java.util.*;
public record LibrarySnapshot(UUID projectId,Long profileRevision,LibraryProviderContext provider,Categories categories) {
 public enum Status { AVAILABLE, EMPTY, UNSUPPORTED, UNAVAILABLE, FAILED }
 public enum Diagnostic { INCOMPLETE, DISCOVERY_FAILED, RUNTIME_UNAVAILABLE, RUNTIME_PROTOCOL_UNVERIFIED, PROFILE_UNAVAILABLE, WORKSPACE_UNAVAILABLE, LIMIT_EXCEEDED, MCP_EXECUTION_CONTEXT_REQUIRED, MCP_TOOLS_ONLY, PROJECT_AGENT_FILES_ONLY, AGENT_FILES_ONLY, UNSAFE_RESOURCE }
 public record Item(String id,String name,String description,String source) {}
 public record Category(Status status,List<Item> items,List<Diagnostic> diagnostics) { public Category {items=List.copyOf(items);diagnostics=List.copyOf(diagnostics);} }
 public record Categories(Category skills,Category agents,Category flows,Category tools) {
  public static Categories unavailable(){var category=new Category(Status.UNAVAILABLE,List.of(),List.of(Diagnostic.RUNTIME_UNAVAILABLE));return new Categories(category,category,category,category);}
 }
}
