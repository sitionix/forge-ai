package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.domain.model.GitHeadState;
import com.sitionix.forgeagent.domain.model.GitHeadType;
import com.sitionix.forgeagent.domain.model.GitLocalRepositoryState;
import com.sitionix.forgeagent.domain.model.GitConflictState;
import com.sitionix.forgeagent.domain.model.GitOperationState;
import com.sitionix.forgeagent.domain.model.GitRemoteInspection;
import com.sitionix.forgeagent.domain.model.GitUpstreamRelation;
import com.sitionix.forgeagent.domain.model.GitUpstreamState;
import com.sitionix.forgeagent.domain.model.GitWorkingTreeState;
import com.sitionix.forgeagent.domain.port.GitExecutionException;
import com.sitionix.forgeagent.domain.port.GitRemoteRejectedException;
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
            throw new GitRemoteRejectedException("Remote is not reachable.");
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
    public GitLocalRepositoryState inspectLocalRepository(final Path repositoryPath) {
        if (Files.exists(repositoryPath.resolve("invalid-git-checkout"))) {
            return GitLocalRepositoryState.invalid();
        }
        return GitLocalRepositoryState.valid(
                new GitHeadState(GitHeadType.BRANCH, "main", "abcdef1234567890"),
                GitWorkingTreeState.CLEAN,
                GitConflictState.NONE,
                GitOperationState.NORMAL,
                new GitUpstreamState("origin/main", GitUpstreamRelation.BEHIND)
        );
    }

    @Override
    public GitLocalRepositoryState refreshRemoteState(final Path repositoryPath) {
        return this.inspectLocalRepository(repositoryPath);
    }

    @Override
    public GitLocalRepositoryState pullFastForward(final Path repositoryPath) {
        return this.inspectLocalRepository(repositoryPath).withUpstreamRelation(GitUpstreamRelation.UP_TO_DATE);
    }

    @Override
    public void clone(final String remoteUrl, final Path targetPath) {
        try {
            Files.createDirectories(targetPath.resolve(".git"));
            if (remoteUrl.contains("invalid-checkout")) {
                Files.writeString(targetPath.resolve("invalid-git-checkout"), "invalid");
            }
        } catch (final IOException exception) {
            throw new GitExecutionException("Clone failed.", exception);
        }
    }
}
