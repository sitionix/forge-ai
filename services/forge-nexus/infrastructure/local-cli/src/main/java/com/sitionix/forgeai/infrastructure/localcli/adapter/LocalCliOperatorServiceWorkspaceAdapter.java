package com.sitionix.forgeai.infrastructure.localcli.adapter;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDefaultMode;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceWorkspaceState;
import com.sitionix.forgeai.domain.port.GitRepositoryPort;
import com.sitionix.forgeai.domain.port.OperatorServiceWorkspacePort;
import java.io.IOException;
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

    private final GitRepositoryPort gitRepositoryPort;

    public LocalCliOperatorServiceWorkspaceAdapter(final GitRepositoryPort gitRepositoryPort) {
        this.gitRepositoryPort = gitRepositoryPort;
    }

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
        final boolean gitRepository;
        try {
            gitRepository = this.gitRepositoryPort.isInsideWorkTree(path);
        } catch (IllegalArgumentException exception) {
            this.addGitInspectionWarning(warnings, exception);
            return new OperatorServiceWorkspaceState(
                    configuredPath,
                    path.toString(),
                    repository,
                    cloneUrl,
                    true,
                    false,
                    null,
                    null,
                    true,
                    warnings
            );
        }
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
        final String branch = this.currentBranch(path, warnings);
        final String defaultBranch = this.defaultBranch(path, warnings);
        return new OperatorServiceWorkspaceState(
                configuredPath,
                path.toString(),
                repository,
                cloneUrl,
                true,
                true,
                branch,
                defaultBranch,
                this.dirty(path, warnings),
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
        try {
            this.gitRepositoryPort.clone(cloneUrl, path);
        } catch (IllegalArgumentException exception) {
            return this.inspect(serviceId, configuredPath, repository);
        }
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
        if (this.hasGitInspectionWarning(before)) {
            return before;
        }
        final Path path = Path.of(before.absolutePath());
        final String defaultBranch = before.defaultBranch();
        if (!this.hasText(defaultBranch)) {
            return before;
        }
        if (before.dirty()) {
            if (mode == OperatorServiceDefaultMode.COMMIT) {
                this.gitRepositoryPort.addAll(path);
                this.gitRepositoryPort.commit(
                        path,
                        "Forge AI",
                        "forge-ai@sitionix.local",
                        this.defaultCommitMessage(before.branch())
                );
            } else if (mode == OperatorServiceDefaultMode.STASH) {
                this.gitRepositoryPort.stash(path, "forge-ai default " + this.ticketKey(before.branch()) + " " + Instant.now());
            } else {
                return this.withWarning(before, "Workspace has local changes. Commit or stash before defaulting.");
            }
        }
        this.tryFetch(path, defaultBranch);
        this.gitRepositoryPort.checkout(path, defaultBranch);
        this.tryPull(path, defaultBranch);
        return this.inspect(serviceId, configuredPath, repository);
    }

    private void tryFetch(final Path path, final String branch) {
        try {
            this.gitRepositoryPort.fetch(path, "origin", branch);
        } catch (IllegalArgumentException ignored) {
            // Local-only workspaces may not have origin configured.
        }
    }

    private void tryPull(final Path path, final String branch) {
        try {
            this.gitRepositoryPort.pullFastForwardOnly(path, "origin", branch);
        } catch (IllegalArgumentException ignored) {
            // Local-only workspaces may not have origin configured.
        }
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
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.exists(current.resolve("config/services.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
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

    private String text(final String result) {
        final String value = result == null ? "" : result.trim();
        return value.isEmpty() ? null : value;
    }

    private String currentBranch(final Path path, final List<String> warnings) {
        try {
            return this.text(this.gitRepositoryPort.currentBranch(path));
        } catch (IllegalArgumentException exception) {
            this.addGitInspectionWarning(warnings, exception);
            return null;
        }
    }

    private String defaultBranch(final Path path, final List<String> warnings) {
        try {
            return this.gitRepositoryPort.defaultBranch(path, DEFAULT_BRANCH_CANDIDATES);
        } catch (IllegalArgumentException exception) {
            this.addGitInspectionWarning(warnings, exception);
            return null;
        }
    }

    private boolean dirty(final Path path, final List<String> warnings) {
        try {
            return this.hasText(this.gitRepositoryPort.statusPorcelain(path));
        } catch (IllegalArgumentException exception) {
            this.addGitInspectionWarning(warnings, exception);
            return true;
        }
    }

    private void addGitInspectionWarning(final List<String> warnings, final IllegalArgumentException exception) {
        final String message = this.firstLine(exception.getMessage());
        final String warning = "Git workspace inspection failed: "
                + (this.hasText(message) ? message : exception.getClass().getSimpleName());
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private boolean hasGitInspectionWarning(final OperatorServiceWorkspaceState state) {
        return state.warnings() != null && state.warnings().stream()
                .anyMatch(warning -> warning != null && warning.startsWith("Git workspace inspection failed:"));
    }

    private String firstLine(final String value) {
        if (value == null) {
            return null;
        }
        final int index = value.indexOf('\n');
        return index < 0 ? value : value.substring(0, index);
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
}
