package com.sitionix.forgeagent.infrastructure.git;

import com.sitionix.forgeagent.domain.model.GitRemoteInspection;
import com.sitionix.forgeagent.domain.port.GitOperationException;
import com.sitionix.forgeagent.domain.port.GitRepositoryPort;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GitRepositoryAdapter implements GitRepositoryPort {

    private static final GitCommandExecutionPolicy INSPECT_REMOTE_POLICY = new GitCommandExecutionPolicy(Duration.ofSeconds(15));
    private static final GitCommandExecutionPolicy CLONE_POLICY = new GitCommandExecutionPolicy(Duration.ofMinutes(30));

    private final GitCommandRunner commandRunner;

    @Override
    public GitRemoteInspection inspectRemote(final String remoteUrl) {
        final GitCommandResult result = this.commandRunner.run(List.of("git", "ls-remote", remoteUrl), INSPECT_REMOTE_POLICY);
        if (result.exitCode() != 0) {
            throw new GitOperationException("Git remote is not reachable.");
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
        final String repositoryName = lastSegment.endsWith(".git")
                ? lastSegment.substring(0, lastSegment.length() - 4)
                : lastSegment;
        if (repositoryName.isBlank()) {
            throw new GitOperationException("Git repository name could not be resolved.");
        }
        return repositoryName;
    }

    @Override
    public void clone(final String remoteUrl, final Path targetPath) {
        final GitCommandResult result = this.commandRunner.run(List.of("git", "clone", remoteUrl, targetPath.toString()), CLONE_POLICY);
        if (result.exitCode() != 0) {
            throw new GitOperationException("Git clone failed.");
        }
    }
}
