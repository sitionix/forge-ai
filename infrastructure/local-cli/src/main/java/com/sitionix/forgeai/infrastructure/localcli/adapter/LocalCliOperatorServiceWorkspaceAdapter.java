package com.sitionix.forgeai.infrastructure.localcli.adapter;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDefaultMode;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceWorkspaceState;
import com.sitionix.forgeai.domain.port.OperatorServiceWorkspacePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LocalCliOperatorServiceWorkspaceAdapter implements OperatorServiceWorkspacePort {

    private static final List<String> DEFAULT_BRANCH_CANDIDATES = List.of("develop", "main", "master");
    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile("(SITIONIX-\\d+)");

    @Override
    public OperatorServiceWorkspaceState inspect(
            final String serviceId,
            final String configuredPath,
            final String repository
    ) {
        final Path path = this.resolvePath(configuredPath);
        final boolean exists = Files.isDirectory(path);
        final List<String> warnings = new ArrayList<>();
        final String cloneUrl = this.cloneUrl(repository);
        if (!exists) {
            return new OperatorServiceWorkspaceState(
                    configuredPath,
                    path.toString(),
                    repository,
                    cloneUrl,
                    false,
                    false,
                    null,
                    null,
                    false,
                    warnings
            );
        }
        final boolean gitRepository = this.git(path, "rev-parse", "--is-inside-work-tree").success();
        if (!gitRepository) {
            warnings.add("Path exists but is not a git repository.");
            return new OperatorServiceWorkspaceState(
                    configuredPath,
                    path.toString(),
                    repository,
                    cloneUrl,
                    true,
                    false,
                    null,
                    null,
                    false,
                    warnings
            );
        }
        return new OperatorServiceWorkspaceState(
                configuredPath,
                path.toString(),
                repository,
                cloneUrl,
                true,
                true,
                this.text(this.git(path, "branch", "--show-current")),
                this.defaultBranch(path),
                this.hasText(this.git(path, "status", "--porcelain").stdout()),
                warnings
        );
    }

    @Override
    public OperatorServiceWorkspaceState cloneRepository(
            final String serviceId,
            final String configuredPath,
            final String repository
    ) {
        final Path path = this.resolvePath(configuredPath);
        final String cloneUrl = this.cloneUrl(repository);
        if (Files.exists(path)) {
            return this.inspect(serviceId, configuredPath, repository);
        }
        if (!this.hasText(cloneUrl)) {
            return this.inspect(serviceId, configuredPath, repository);
        }
        try {
            final Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            return this.inspect(serviceId, configuredPath, repository);
        }
        this.run(path.getParent(), "git", "clone", cloneUrl, path.getFileName().toString());
        return this.inspect(serviceId, configuredPath, repository);
    }

    @Override
    public OperatorServiceWorkspaceState resetToDefaultBranch(
            final String serviceId,
            final String configuredPath,
            final String repository,
            final OperatorServiceDefaultMode mode
    ) {
        final OperatorServiceWorkspaceState before = this.inspect(serviceId, configuredPath, repository);
        if (!before.gitRepository()) {
            return before;
        }
        final Path path = Path.of(before.absolutePath());
        final String defaultBranch = before.defaultBranch();
        if (!this.hasText(defaultBranch)) {
            return before;
        }
        if (before.dirty()) {
            if (mode == OperatorServiceDefaultMode.COMMIT) {
                this.git(path, "add", "-A");
                this.git(
                        path,
                        "-c",
                        "user.name=Forge AI",
                        "-c",
                        "user.email=forge-ai@sitionix.local",
                        "commit",
                        "-m",
                        this.defaultCommitMessage(before.branch())
                );
            } else if (mode == OperatorServiceDefaultMode.STASH) {
                this.git(path, "stash", "push", "-u", "-m", "forge-ai default " + this.ticketKey(before.branch())
                        + " " + Instant.now());
            } else {
                return this.withWarning(before, "Workspace has local changes. Commit or stash before defaulting.");
            }
        }
        this.git(path, "fetch", "origin", defaultBranch);
        this.git(path, "checkout", defaultBranch);
        this.git(path, "pull", "--ff-only", "origin", defaultBranch);
        return this.inspect(serviceId, configuredPath, repository);
    }

    private Path resolvePath(final String configuredPath) {
        if (!this.hasText(configuredPath)) {
            return this.forgeAiRoot();
        }
        final Path raw = Path.of(configuredPath);
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        final Path forgeAiRoot = this.forgeAiRoot();
        if (forgeAiRoot.getFileName() != null && forgeAiRoot.getFileName().toString().equals(configuredPath)) {
            return forgeAiRoot;
        }
        final Path workspaceRoot = forgeAiRoot.getParent();
        return (workspaceRoot == null ? forgeAiRoot : workspaceRoot).resolve(configuredPath).normalize();
    }

    private Path forgeAiRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("boot/src/main/resources/services.yaml"))
                    && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private String defaultBranch(final Path path) {
        final String originHead = this.text(this.git(path, "symbolic-ref", "--short", "refs/remotes/origin/HEAD"));
        if (this.hasText(originHead) && originHead.startsWith("origin/")) {
            return originHead.substring("origin/".length());
        }
        for (String candidate : DEFAULT_BRANCH_CANDIDATES) {
            if (this.git(path, "rev-parse", "--verify", "origin/" + candidate).success()
                    || this.git(path, "rev-parse", "--verify", candidate).success()) {
                return candidate;
            }
        }
        return null;
    }

    private CommandResult git(final Path path, final String... args) {
        final List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(path.toString());
        command.addAll(List.of(args));
        return this.run(null, command.toArray(String[]::new));
    }

    private CommandResult run(final Path cwd, final String... command) {
        try {
            final ProcessBuilder builder = new ProcessBuilder(command);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            final Process process = builder.start();
            final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            final int exitCode = process.waitFor();
            return new CommandResult(exitCode, stdout, stderr);
        } catch (IOException exception) {
            return new CommandResult(-1, "", exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", exception.getMessage());
        }
    }

    private String cloneUrl(final String repository) {
        if (!this.hasText(repository)) {
            return null;
        }
        final String value = repository.trim();
        if (value.startsWith("git@") || value.startsWith("https://") || value.startsWith("http://")) {
            return value;
        }
        return "git@github.com:" + value + ".git";
    }

    private String text(final CommandResult result) {
        final String value = result.stdout().trim();
        return value.isEmpty() ? null : value;
    }

    private OperatorServiceWorkspaceState withWarning(
            final OperatorServiceWorkspaceState state,
            final String warning
    ) {
        final List<String> warnings = new ArrayList<>();
        if (state.warnings() != null) {
            warnings.addAll(state.warnings());
        }
        warnings.add(warning);
        return new OperatorServiceWorkspaceState(
                state.configuredPath(),
                state.absolutePath(),
                state.repository(),
                state.cloneUrl(),
                state.exists(),
                state.gitRepository(),
                state.branch(),
                state.defaultBranch(),
                state.dirty(),
                warnings
        );
    }

    private String defaultCommitMessage(final String branch) {
        return "[" + this.ticketKey(branch) + "] - default local service workspace";
    }

    private String ticketKey(final String branch) {
        final Matcher matcher = TICKET_KEY_PATTERN.matcher(branch == null ? "" : branch);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "SITIONIX-0";
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {

        private boolean success() {
            return this.exitCode == 0;
        }
    }
}
