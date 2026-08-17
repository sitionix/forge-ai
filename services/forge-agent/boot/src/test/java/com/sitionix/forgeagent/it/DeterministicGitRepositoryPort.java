package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.domain.model.GitRemoteInspection;
import com.sitionix.forgeagent.domain.port.GitOperationException;
import com.sitionix.forgeagent.domain.port.GitRepositoryPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DeterministicGitRepositoryPort implements GitRepositoryPort {

    @Override
    public GitRemoteInspection inspectRemote(final String remoteUrl) {
        if (remoteUrl.contains("missing")) {
            throw new GitOperationException("Remote is not reachable.");
        }
        return new GitRemoteInspection(this.resolveRepositoryName(remoteUrl));
    }

    @Override
    public String resolveRepositoryName(final String remoteUrl) {
        final String trimmed = remoteUrl == null ? "" : remoteUrl.trim();
        final String withoutTrailingSlash = trimmed.replaceAll("/+$", "");
        final int lastSlash = withoutTrailingSlash.lastIndexOf('/');
        final int lastColon = withoutTrailingSlash.lastIndexOf(':');
        final int separator = Math.max(lastSlash, lastColon);
        final String lastSegment = separator >= 0 ? withoutTrailingSlash.substring(separator + 1) : withoutTrailingSlash;
        return lastSegment.endsWith(".git") ? lastSegment.substring(0, lastSegment.length() - 4) : lastSegment;
    }

    @Override
    public void clone(final String remoteUrl, final Path targetPath) {
        try {
            Files.createDirectories(targetPath.resolve(".git"));
        } catch (final IOException exception) {
            throw new GitOperationException("Clone failed.", exception);
        }
    }
}
