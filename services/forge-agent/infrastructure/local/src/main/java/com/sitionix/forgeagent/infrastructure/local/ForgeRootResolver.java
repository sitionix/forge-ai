package com.sitionix.forgeagent.infrastructure.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspaceException;
import org.springframework.stereotype.Component;

@Component
public final class ForgeRootResolver {

    private final Path startDirectory;

    ForgeRootResolver() {
        this(Path.of("").toAbsolutePath().normalize());
    }

    public ForgeRootResolver(final Path startDirectory) {
        this.startDirectory = startDirectory.toAbsolutePath().normalize();
    }

    Path resolveForgeRoot() {
        Path current = this.startDirectory;
        while (current != null) {
            if (this.isGitRootMarker(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new LocalProjectWorkspaceException("Forge root could not be resolved.");
    }

    private boolean isGitRootMarker(final Path gitMarker) {
        return Files.isDirectory(gitMarker, LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(gitMarker, LinkOption.NOFOLLOW_LINKS);
    }
}
