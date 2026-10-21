package com.sitionix.forgeagent.domain.model.library;

import java.util.UUID;
public record LibrarySnapshot(UUID projectId, LibraryProviderContext provider, LibraryCategories categories) {}
