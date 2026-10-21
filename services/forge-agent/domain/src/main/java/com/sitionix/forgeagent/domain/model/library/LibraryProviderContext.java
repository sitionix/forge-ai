package com.sitionix.forgeagent.domain.model.library;

public record LibraryProviderContext(long profileRevision, String providerId, String modelId, String effortId,
 String providerDisplayName, String modelDisplayName) {
 public LibraryProviderContext {
  if (profileRevision<0 || providerId==null || providerId.isBlank() || modelId==null || modelId.isBlank()
      || providerId.length()>128 || modelId.length()>256) throw new IllegalArgumentException("Invalid library provider context");
 }
}
