package com.sitionix.forgeagent.application.mcp;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/** Management operations. Wired only when the management prerequisite enables MCP. */
public class McpConnectionService {
    private static final String PURPOSE = "credential";
    private final McpConnectionRepository repository;
    private final ProjectRepository projects;
    private final ForgeInstanceIdentityRepository identity;
    private final McpCredentialCipher cipher;

    public McpConnectionService(McpConnectionRepository repository, ProjectRepository projects,
                                ForgeInstanceIdentityRepository identity, McpCredentialCipher cipher) {
        this.repository = Objects.requireNonNull(repository);
        this.projects = Objects.requireNonNull(projects);
        this.identity = Objects.requireNonNull(identity);
        this.cipher = Objects.requireNonNull(cipher);
    }

    @Transactional
    public McpConnection create(String displayName, URI endpoint, McpAuthType authType,
                                McpProjectAccess access, McpCredentialSecret secret) {
        validate(displayName, endpoint, authType, access);
        if (authType == McpAuthType.NONE && secret != null) throw new IllegalArgumentException("Invalid credential change");
        if (secret != null && secret.type() != authType) throw new IllegalArgumentException("Invalid credential change");
        UUID installation = identity.getOrCreate(), id = UUID.randomUUID();
        Instant now = Instant.now();
        var connection = new McpConnection(id, installation, displayName.strip(), endpoint, authType, false,
                access, Set.of(), secret != null, now, now, null, null);
        repository.insert(new McpConnectionState(connection,
                secret == null ? null : cipher.encrypt(installation,id,PURPOSE,secret.bytes())));
        return connection;
    }

    public List<McpConnection> list() { return repository.findAll(identity.getOrCreate()); }

    public McpConnection get(UUID id) { return repository.findById(identity.getOrCreate(), id).orElseThrow(() -> new NoSuchElementException("MCP connection not found")); }

    public McpProjectAccess policy(UUID id) { return get(id).projectAccess(); }

    public boolean allows(UUID id, UUID projectId, String toolName, String schemaFingerprint) {
        if (projectId == null || projects.findById(projectId).isEmpty()) return false;
        return repository.findById(identity.getOrCreate(), id).filter(McpConnection::enabled)
                .filter(c -> c.authType() == McpAuthType.NONE || c.credentialConfigured())
                .filter(c -> c.projectAccess().allows(projectId))
                .map(c -> c.allowedTools().contains(new McpAllowedTool(toolName, schemaFingerprint))).orElse(false);
    }

    @Transactional
    public McpConnection update(UUID id, String displayName, URI endpoint, McpAuthType authType,
                                McpProjectAccess access, McpCredentialChange change, McpCredentialSecret replacement) {
        validate(displayName, endpoint, authType, access);
        if (change == null || (change == McpCredentialChange.REPLACE) != (replacement != null)
                || (authType == McpAuthType.NONE && change == McpCredentialChange.REPLACE)
                || (replacement != null && replacement.type() != authType)) throw new IllegalArgumentException("Invalid credential change");
        UUID installation = identity.getOrCreate();
        return repository.change(installation,id,state -> {
            McpConnection current = state.connection();
            if (change == McpCredentialChange.KEEP && current.credentialConfigured()
                    && (current.authType() != authType || !current.endpoint().equals(endpoint)))
                throw new IllegalArgumentException("Credential identity changed");
            McpEncryptedCredential encrypted = switch (change) {
                case KEEP -> state.credential();
                case REMOVE -> null;
                case REPLACE -> cipher.encrypt(installation,id,PURPOSE,replacement.bytes());
            };
            if (authType == McpAuthType.NONE) encrypted = null;
            boolean identityChanged = current.authType() != authType || !current.endpoint().equals(endpoint)
                    || change != McpCredentialChange.KEEP;
            var updated = new McpConnection(id, installation, displayName.strip(), endpoint, authType,
                    current.enabled(), access, identityChanged ? Set.of() : current.allowedTools(), encrypted != null,
                    current.createdAt(), Instant.now(), identityChanged ? null : current.checkedAt(),
                    identityChanged ? null : current.safeDiagnostic());
            return new McpConnectionState(updated,encrypted);
        }).orElseThrow(() -> new NoSuchElementException("MCP connection not found")).connection();
    }

    @Transactional
    public McpConnection setEnabled(UUID id, boolean enabled) {
        return repository.change(identity.getOrCreate(),id,state -> {
            var current = state.connection();
            var updated = new McpConnection(current.id(),current.installationId(),current.displayName(),current.endpoint(),
                    current.authType(),enabled,current.projectAccess(),current.allowedTools(),current.credentialConfigured(),
                    current.createdAt(),Instant.now(),current.checkedAt(),current.safeDiagnostic());
            return new McpConnectionState(updated,state.credential());
        }).orElseThrow(() -> new NoSuchElementException("MCP connection not found")).connection();
    }

    @Transactional
    public void remove(UUID id) { repository.delete(identity.getOrCreate(),id); }

    @Transactional
    public void reencrypt(UUID id) {
        repository.change(identity.getOrCreate(),id,state -> {
            if (state.credential() == null) return state;
            var current = state.connection();
            byte[] plaintext = cipher.decrypt(current.installationId(),id,PURPOSE,state.credential());
            try { return new McpConnectionState(current,cipher.encrypt(current.installationId(),id,PURPOSE,plaintext)); }
            finally { Arrays.fill(plaintext,(byte)0); }
        }).orElseThrow(() -> new NoSuchElementException("MCP connection not found"));
    }

    private void validate(String name, URI endpoint, McpAuthType authType, McpProjectAccess access) {
        if (name == null || name.isBlank() || name.length() > 255 || endpoint == null || authType == null || access == null)
            throw new IllegalArgumentException("Invalid MCP connection");
        String scheme = endpoint.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || endpoint.getHost() == null || endpoint.getHost().isBlank() || endpoint.getRawUserInfo() != null
                || endpoint.getRawFragment() != null || endpoint.getRawQuery() != null)
            throw new IllegalArgumentException("Invalid MCP endpoint");
        for (UUID projectId : access.projectIds())
            if (projects.findById(projectId).isEmpty()) throw new IllegalArgumentException("Unknown project");
    }
}
