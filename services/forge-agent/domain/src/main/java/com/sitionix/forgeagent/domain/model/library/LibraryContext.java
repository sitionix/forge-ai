package com.sitionix.forgeagent.domain.model.library;

import java.util.List;
import java.util.UUID;
import java.nio.file.Path;
public record LibraryContext(UUID projectId, LibraryProviderContext provider, Path cwd, List<Path> workspaceRoots) {
 public LibraryContext { workspaceRoots=List.copyOf(workspaceRoots); }
}
