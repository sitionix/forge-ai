package com.sitionix.forgeagent.infrastructure.git;

import com.sitionix.forgeagent.domain.model.GitHeadState;
import com.sitionix.forgeagent.domain.model.GitHeadType;
import com.sitionix.forgeagent.domain.model.GitLocalRepositoryState;
import com.sitionix.forgeagent.domain.model.GitRemoteInspection;
import com.sitionix.forgeagent.domain.model.GitWorkingTreeState;
import com.sitionix.forgeagent.domain.port.GitExecutionException;
import com.sitionix.forgeagent.domain.port.GitOperationException;
import com.sitionix.forgeagent.domain.port.GitRemoteRejectedException;
import com.sitionix.forgeagent.domain.port.GitRepositoryPort;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GitRepositoryAdapter implements GitRepositoryPort {

    private static final GitCommandExecutionPolicy INSPECT_REMOTE_POLICY = new GitCommandExecutionPolicy(Duration.ofSeconds(15));
    private static final GitCommandExecutionPolicy INSPECT_LOCAL_POLICY = new GitCommandExecutionPolicy(Duration.ofSeconds(10));
    private static final GitCommandExecutionPolicy CLONE_POLICY = new GitCommandExecutionPolicy(Duration.ofMinutes(30));

    private final GitCommandRunner commandRunner;

    @Override
    public GitRemoteInspection inspectRemote(final String remoteUrl) {
        final GitCommandResult result = this.commandRunner.run(List.of("git", "ls-remote", remoteUrl), INSPECT_REMOTE_POLICY);
        if (result.exitCode() != 0) {
            throw new GitRemoteRejectedException("Git remote is not reachable.");
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
    public GitLocalRepositoryState inspectLocalRepository(final Path repositoryPath) {
        final GitCommandResult result = this.commandRunner.run(List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "status",
                "--porcelain=v2",
                "--branch",
                "--untracked-files=normal"
        ), INSPECT_LOCAL_POLICY);
        if (result.exitCode() != 0) {
            if (this.isInvalidRepository(result.stderr())) {
                return GitLocalRepositoryState.invalid();
            }
            throw new GitExecutionException("Git local repository inspection failed.");
        }
        return this.parseStatus(result.stdout());
    }

    @Override
    public void clone(final String remoteUrl, final Path targetPath) {
        final GitCommandResult result = this.commandRunner.run(List.of("git", "clone", remoteUrl, targetPath.toString()), CLONE_POLICY);
        if (result.exitCode() != 0) {
            throw new GitExecutionException("Git clone failed.");
        }
    }

    private GitLocalRepositoryState parseStatus(final String output) {
        final Map<String, String> branchHeaders = new HashMap<>();
        boolean dirty = false;
        for (final String line : output.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("# branch.")) {
                final int separator = line.indexOf(' ', "# branch.".length());
                if (separator < 0 || separator == line.length() - 1) {
                    throw new GitExecutionException("Git local repository status output is malformed.");
                }
                branchHeaders.put(line.substring("# branch.".length(), separator), line.substring(separator + 1));
            } else if (!line.startsWith("#")) {
                dirty = true;
            }
        }
        final String head = branchHeaders.get("head");
        final String oid = branchHeaders.get("oid");
        if (head == null || oid == null) {
            throw new GitExecutionException("Git local repository status output is malformed.");
        }
        final String commit = "(initial)".equals(oid) ? null : oid;
        final GitHeadState headState = "(detached)".equals(head)
                ? new GitHeadState(GitHeadType.DETACHED, null, commit)
                : new GitHeadState(GitHeadType.BRANCH, head, commit);
        return new GitLocalRepositoryState(true, headState, dirty ? GitWorkingTreeState.DIRTY : GitWorkingTreeState.CLEAN);
    }

    private boolean isInvalidRepository(final String stderr) {
        final String normalized = stderr == null ? "" : stderr.toLowerCase();
        return normalized.contains("not a git repository")
                || normalized.contains("not a git directory")
                || normalized.contains("cannot change to");
    }
}
