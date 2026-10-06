package com.sitionix.forgeagent.infrastructure.local;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The managed workspace is independent of the source checkout and control HOME. */
@Component
public final class ForgeRootResolver {
    private final Path managedRoot;

    public ForgeRootResolver(@Value("${forge.agent.workspace-root:/srv/forge/workspaces/forge-projects}") Path managedRoot) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    Path resolveManagedRoot() { return this.managedRoot; }
}
