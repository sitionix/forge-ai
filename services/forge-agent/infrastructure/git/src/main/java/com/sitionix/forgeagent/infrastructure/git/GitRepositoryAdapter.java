package com.sitionix.forgeagent.infrastructure.git;

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
import com.sitionix.forgeagent.domain.port.GitOperationException;
import com.sitionix.forgeagent.domain.port.GitRemoteRejectedException;
import com.sitionix.forgeagent.domain.port.GitRepositoryPort;
import com.sitionix.forgeagent.domain.port.GitUnsafeRepositoryStateException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.io.IOException;
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
    private static final GitCommandExecutionPolicy FETCH_POLICY = new GitCommandExecutionPolicy(Duration.ofMinutes(10));
    private static final GitCommandExecutionPolicy FAST_FORWARD_POLICY = new GitCommandExecutionPolicy(Duration.ofMinutes(5));
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
        final GitCommandResult rootResult = this.commandRunner.run(List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "rev-parse",
                "--show-toplevel"
        ), INSPECT_LOCAL_POLICY);
        if (rootResult.exitCode() != 0) {
            return GitLocalRepositoryState.invalid();
        }
        if (!this.isRequestedRepositoryRoot(repositoryPath, rootResult.stdout())) {
            return GitLocalRepositoryState.invalid();
        }

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
            throw new GitExecutionException("Git local repository inspection failed.");
        }
        return this.parseStatus(result.stdout(), this.inspectOperationState(repositoryPath));
    }

    @Override
    public void clone(final String remoteUrl, final Path targetPath) {
        final GitCommandResult result = this.commandRunner.run(List.of("git", "clone", remoteUrl, targetPath.toString()), CLONE_POLICY);
        if (result.exitCode() != 0) {
            throw new GitExecutionException("Git clone failed.");
        }
    }

    @Override
    public GitLocalRepositoryState pullFastForward(final Path repositoryPath) {
        final GitLocalRepositoryState initialState = this.inspectLocalRepository(repositoryPath);
        this.requirePullAllowed(initialState);
        final GitUpstreamFetchTarget fetchTarget = this.resolveFetchTarget(repositoryPath, initialState);
        final GitCommandResult fetchResult = this.commandRunner.run(List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "fetch",
                fetchTarget.remote(),
                fetchTarget.mergeRef() + ":" + fetchTarget.remoteTrackingRef()
        ), FETCH_POLICY);
        if (fetchResult.exitCode() != 0) {
            throw new GitExecutionException("Git fetch failed.");
        }

        final GitLocalRepositoryState afterFetchState = this.inspectLocalRepository(repositoryPath);
        this.requireStablePullTarget(initialState, afterFetchState);
        if (afterFetchState.upstream().relation() == GitUpstreamRelation.UP_TO_DATE) {
            return afterFetchState;
        }
        if (afterFetchState.upstream().relation() != GitUpstreamRelation.BEHIND) {
            throw new GitUnsafeRepositoryStateException("Git repository is not safe to pull.", afterFetchState);
        }

        final GitCommandResult mergeResult = this.commandRunner.run(List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "merge",
                "--ff-only",
                afterFetchState.upstream().ref()
        ), FAST_FORWARD_POLICY);
        if (mergeResult.exitCode() != 0) {
            throw new GitExecutionException("Git fast-forward pull failed.");
        }
        return this.inspectLocalRepository(repositoryPath);
    }

    private GitLocalRepositoryState parseStatus(final String output, final GitOperationState operationState) {
        final Map<String, String> branchHeaders = new HashMap<>();
        boolean dirty = false;
        boolean conflicted = false;
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
                if (line.startsWith("u ")) {
                    conflicted = true;
                }
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
        return GitLocalRepositoryState.valid(
                headState,
                dirty ? GitWorkingTreeState.DIRTY : GitWorkingTreeState.CLEAN,
                conflicted ? GitConflictState.CONFLICTED : GitConflictState.NONE,
                operationState,
                this.resolveUpstream(branchHeaders)
        );
    }

    private GitUpstreamState resolveUpstream(final Map<String, String> branchHeaders) {
        final String upstream = branchHeaders.get("upstream");
        if (upstream == null || upstream.isBlank()) {
            return null;
        }
        final String aheadBehind = branchHeaders.get("ab");
        if (aheadBehind == null) {
            throw new GitExecutionException("Git local repository status output is malformed.");
        }
        final String[] parts = aheadBehind.split(" ");
        if (parts.length != 2 || !parts[0].startsWith("+") || !parts[1].startsWith("-")) {
            throw new GitExecutionException("Git local repository status output is malformed.");
        }
        final int ahead = this.parseCount(parts[0].substring(1));
        final int behind = this.parseCount(parts[1].substring(1));
        final GitUpstreamRelation relation;
        if (ahead > 0 && behind > 0) {
            relation = GitUpstreamRelation.DIVERGED;
        } else if (ahead > 0) {
            relation = GitUpstreamRelation.AHEAD;
        } else if (behind > 0) {
            relation = GitUpstreamRelation.BEHIND;
        } else {
            relation = GitUpstreamRelation.UP_TO_DATE;
        }
        return new GitUpstreamState(upstream, relation);
    }

    private int parseCount(final String value) {
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException exception) {
            throw new GitExecutionException("Git local repository status output is malformed.", exception);
        }
    }

    private GitOperationState inspectOperationState(final Path repositoryPath) {
        final GitCommandResult result = this.commandRunner.run(List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "rev-parse",
                "--git-path",
                "MERGE_HEAD",
                "--git-path",
                "CHERRY_PICK_HEAD",
                "--git-path",
                "REBASE_HEAD",
                "--git-path",
                "rebase-merge",
                "--git-path",
                "rebase-apply"
        ), INSPECT_LOCAL_POLICY);
        if (result.exitCode() != 0) {
            throw new GitExecutionException("Git operation state inspection failed.");
        }
        final boolean operationInProgress = result.stdout().lines()
                .filter(line -> !line.isBlank())
                .map(Path::of)
                .map(path -> path.isAbsolute() ? path : repositoryPath.resolve(path))
                .anyMatch(Files::exists);
        return operationInProgress ? GitOperationState.IN_PROGRESS : GitOperationState.NORMAL;
    }

    private GitUpstreamFetchTarget resolveFetchTarget(final Path repositoryPath, final GitLocalRepositoryState state) {
        final String branch = state.head().ref();
        final String remote = this.readBranchConfig(repositoryPath, branch, "remote");
        final String mergeRef = this.readBranchConfig(repositoryPath, branch, "merge");
        if (remote.isBlank() || mergeRef.isBlank()) {
            throw new GitUnsafeRepositoryStateException("Git repository is not safe to pull.", state);
        }
        return new GitUpstreamFetchTarget(remote, mergeRef, this.toRemoteTrackingRef(state.upstream().ref()));
    }

    private String toRemoteTrackingRef(final String upstreamRef) {
        if (upstreamRef.startsWith("refs/remotes/")) {
            return upstreamRef;
        }
        return "refs/remotes/" + upstreamRef;
    }

    private String readBranchConfig(final Path repositoryPath, final String branch, final String key) {
        final GitCommandResult result = this.commandRunner.run(List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "config",
                "--get",
                "branch." + branch + "." + key
        ), INSPECT_LOCAL_POLICY);
        if (result.exitCode() != 0) {
            return "";
        }
        return result.stdout().trim();
    }

    private void requirePullAllowed(final GitLocalRepositoryState state) {
        if (!state.valid() || !state.pullAllowed()) {
            throw new GitUnsafeRepositoryStateException("Git repository is not safe to pull.", state);
        }
    }

    private void requireStablePullTarget(final GitLocalRepositoryState initialState, final GitLocalRepositoryState candidateState) {
        this.requirePullAllowed(candidateState);
        if (initialState.head().type() != candidateState.head().type()
                || !initialState.head().ref().equals(candidateState.head().ref())
                || !initialState.head().commit().equals(candidateState.head().commit())
                || initialState.upstream() == null
                || candidateState.upstream() == null
                || !initialState.upstream().ref().equals(candidateState.upstream().ref())) {
            throw new GitUnsafeRepositoryStateException("Git repository is not safe to pull.", candidateState);
        }
    }

    private boolean isRequestedRepositoryRoot(final Path repositoryPath, final String output) {
        final List<String> roots = output.lines()
                .filter(line -> !line.isBlank())
                .toList();
        if (roots.size() != 1) {
            throw new GitExecutionException("Git local repository root output is malformed.");
        }
        try {
            final Path requestedPath = repositoryPath.toRealPath();
            final Path recognizedRoot = Path.of(roots.getFirst()).toRealPath();
            return requestedPath.equals(recognizedRoot);
        } catch (final IOException exception) {
            throw new GitExecutionException("Git local repository root could not be resolved.", exception);
        }
    }

    private record GitUpstreamFetchTarget(String remote, String mergeRef, String remoteTrackingRef) {
    }
}
