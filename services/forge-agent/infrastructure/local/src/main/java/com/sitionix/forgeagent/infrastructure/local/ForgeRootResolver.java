package com.sitionix.forgeagent.infrastructure.local;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
final class ForgeRootResolver {

    private final Path startDirectory;

    ForgeRootResolver() {
        this(Path.of("").toAbsolutePath().normalize());
    }

    ForgeRootResolver(final Path startDirectory) {
        this.startDirectory = startDirectory.toAbsolutePath().normalize();
    }

    Path resolveForgeRoot() {
        Path current = this.startDirectory;
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of("").toAbsolutePath().normalize();
    }
}
