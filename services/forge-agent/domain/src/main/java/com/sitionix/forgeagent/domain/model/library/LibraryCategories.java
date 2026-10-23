package com.sitionix.forgeagent.domain.model.library;

public record LibraryCategories(InventoryResult<SkillDescriptor> skills, InventoryResult<AgentDescriptor> agents,
 InventoryResult<FlowDescriptor> flows, InventoryResult<ToolDescriptor> tools) {
 public static LibraryCategories unavailable(InventoryDiagnostic reason) {
  return new LibraryCategories(InventoryResult.unavailable(reason),InventoryResult.unavailable(reason),InventoryResult.unavailable(reason),InventoryResult.unavailable(reason));
 }
 public static LibraryCategories unsupported() { return new LibraryCategories(InventoryResult.unsupported(),InventoryResult.unsupported(),InventoryResult.unsupported(),InventoryResult.unsupported()); }
}
