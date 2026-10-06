package com.sitionix.forgeagent.application.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpConnectionServiceTest {
    @Test void oauthSetupUsesExistingCredentialRowWithoutGrantingAccess() {
        var oauthCipher = mock(McpOAuthCredentialCipher.class);
        var encrypted = new McpEncryptedCredential("fixture", new byte[]{1});
        when(oauthCipher.encrypt(any(),any(),any())).thenReturn(encrypted);
        var sut = new McpConnectionService(repository, projects, () -> installation, cipher, null, oauthCipher,org.mockito.Mockito.mock(McpOAuthClient.class));
        var config = new McpOAuthConfiguration(URI.create("https://oauth.example"), URI.create("https://oauth.example/authorize"),
                URI.create("https://oauth.example/token"), null, "fixture", "client_secret_post", Set.of("tools"), URI.create("https://mcp.example/mcp"));
        var saved = sut.create("OAuth", config.resource(), McpAuthType.OAUTH, McpProjectAccess.selected(Set.of()), null, config,
                new McpOAuthCredentials("client-canary", null));
        assertThat(saved.enabled()).isFalse(); assertThat(saved.credentialConfigured()).isFalse();
        assertThat(saved.oauthConfiguration()).isEqualTo(config); assertThat(saved.oauthAuthorizationId()).isNull();
        assertThat(repository.credential(installation,saved.id())).contains(encrypted);
        assertThat(saved.toString()).doesNotContain("client-canary");
        var renamed = sut.update(saved.id(),"Renamed",saved.endpoint(),McpAuthType.OAUTH,saved.projectAccess(),McpCredentialChange.KEEP,null,null,null);
        assertThat(renamed.oauthConfiguration()).isEqualTo(config); assertThat(renamed.credentialConfigured()).isFalse();
        assertThat(repository.credential(installation,saved.id())).contains(encrypted);
    }
    @Test void disconnectDeletesLocallyBeforeBestEffortProviderRevocation() {
        var oauthCipher=mock(McpOAuthCredentialCipher.class);var provider=mock(McpOAuthClient.class);
        var cfg=new McpOAuthConfiguration(URI.create("https://oauth.example"),URI.create("https://oauth.example/auth"),
                URI.create("https://oauth.example/token"),URI.create("https://oauth.example/revoke"),"client","none",Set.of(),URI.create("https://mcp.example/mcp"));
        UUID id=UUID.randomUUID();var now=Instant.now();
        var c=new McpConnection(id,installation,"OAuth",cfg.resource(),McpAuthType.OAUTH,false,McpProjectAccess.all(),Set.of(),true,
                now,now,null,null,cfg,UUID.randomUUID());
        var encrypted=new McpEncryptedCredential("fixture",new byte[]{1});
        var envelope=new McpOAuthCredentials(null,new McpOAuthTokens("access-canary","refresh-canary",null,null,null));
        repository.insert(new McpConnectionState(c,encrypted));when(oauthCipher.decrypt(installation,id,encrypted)).thenReturn(envelope);
        doAnswer(invocation->{assertThat(repository.findById(installation,id)).isEmpty();
            throw com.sitionix.forgeagent.domain.exception.McpOAuthException.unavailable();}).when(provider).revoke(cfg,envelope);
        var sut=new McpConnectionService(repository,projects,()->installation,cipher,null,oauthCipher,provider);
        assertThatCode(()->sut.remove(id)).doesNotThrowAnyException();
        verify(provider).revoke(cfg,envelope);assertThat(repository.credential(installation,id)).isEmpty();
    }

    private final UUID installation = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final Map<UUID, McpConnection> metadata = new HashMap<>();
    private final Map<UUID, McpEncryptedCredential> secrets = new HashMap<>();
    private Runnable onMutationRead;
    private boolean projectExists = true;
    private final McpConnectionRepository repository = new McpConnectionRepository() {
        public Optional<McpConnection> findById(UUID installationId, UUID id) { return Optional.ofNullable(metadata.get(id)).filter(c -> c.installationId().equals(installationId)); }
        public List<McpConnection> findAll(UUID installationId) { return metadata.values().stream().filter(c -> c.installationId().equals(installationId)).toList(); }
        public Optional<McpEncryptedCredential> credential(UUID installationId, UUID id) { return findById(installationId,id).map(c -> secrets.get(id)); }
        public void insert(McpConnectionState state) { put(state); }
        public Optional<McpConnectionState> change(UUID installationId, UUID id, java.util.function.UnaryOperator<McpConnectionState> mutation) {
            Runnable callback = onMutationRead; onMutationRead = null;
            if (callback != null) callback.run();
            var current = findById(installationId,id);
            if (current.isEmpty()) return Optional.empty();
            var next = mutation.apply(new McpConnectionState(current.orElseThrow(),secrets.get(id)));
            if (next == null) { delete(installationId,id); return Optional.empty(); }
            put(next);
            return Optional.of(next);
        }
        private void put(McpConnectionState state) { var c = state.connection(); metadata.put(c.id(),c); if(state.credential() == null) secrets.remove(c.id()); else secrets.put(c.id(),state.credential()); }
        public Optional<McpConnectionState> delete(UUID installationId, UUID id) {
            var removed=findById(installationId,id).map(c -> new McpConnectionState(c,secrets.get(id)));
            removed.ifPresent(c -> {metadata.remove(id);secrets.remove(id);});return removed;
        }
    };
    private final McpCredentialCipher cipher = new McpCredentialCipher() {
        public McpEncryptedCredential encrypt(UUID i, UUID c, String p, byte[] b) { return new McpEncryptedCredential("test", b); }
        public byte[] decrypt(UUID i, UUID c, String p, McpEncryptedCredential b) { return b.bytes(); }
    };
    private final ProjectRepository projects = new ProjectRepository() {
        public List<Project> findAllOrdered() { return List.of(); }
        public Optional<Project> findById(UUID id) { return projectExists && id.equals(project) ? Optional.of(new Project(id,"P","p", Instant.now(),Instant.now())) : Optional.empty(); }
        public boolean existsByNormalizedName(String name) { return false; }
        public Project save(Project p) { return p; }
        public void deleteById(UUID id) {}
    };
    private final McpConnectionService service = new McpConnectionService(repository, projects, () -> installation, cipher, null, org.mockito.Mockito.mock(McpOAuthCredentialCipher.class),org.mockito.Mockito.mock(McpOAuthClient.class));

    @Test void managementMutationRevokesRuntimeGrant() {
        var gateway = mock(McpGatewayService.class);
        var protectedService = new McpConnectionService(repository, projects, () -> installation, cipher, gateway, org.mockito.Mockito.mock(McpOAuthCredentialCipher.class),org.mockito.Mockito.mock(McpOAuthClient.class));
        var created = protectedService.create("test", URI.create("https://example.org/mcp"),
                McpAuthType.NONE, McpProjectAccess.all(), null, null, null);
        protectedService.setEnabled(created.id(), true);
        verifyNoInteractions(gateway);
        protectedService.setEnabled(created.id(), false);
        protectedService.update(created.id(), "renamed", created.endpoint(), McpAuthType.NONE,
                McpProjectAccess.all(), McpCredentialChange.KEEP, null, null, null);
        protectedService.remove(created.id());
        verify(gateway, times(3)).revokeConnection(created.id());
    }

    @Test void selectedEmptyDeniesAndForeignProjectRejected() {
        var c = service.create("Example", URI.create("https://example.org/mcp"), McpAuthType.NONE, McpProjectAccess.selected(Set.of()), null, null, null);
        assertThat(service.allows(c.id(), project, "any", "hash")).isFalse();
        assertThatThrownBy(() -> service.create("Bad", URI.create("https://example.org"), McpAuthType.NONE, McpProjectAccess.selected(Set.of(UUID.randomUUID())), null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void newConnectionHasNoApprovedToolsAndDuplicateNamesStaySeparate() {
        var a = service.create("Same", URI.create("https://example.org/a"), McpAuthType.NONE, McpProjectAccess.all(), null, null, null);
        var b = service.create("Same", URI.create("https://example.org/b"), McpAuthType.NONE, McpProjectAccess.all(), null, null, null);
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(a.allowedTools()).isEmpty();
        assertThat(service.allows(a.id(), project, "tool", "hash")).isFalse();
        assertThat(service.list()).hasSize(2);
    }

    @Test void credentialTransitionsAndRedaction() {
        var c = service.create("Bearer", URI.create("https://example.org"), McpAuthType.BEARER, McpProjectAccess.all(), null, null, null);
        assertThat(c.credentialConfigured()).isFalse();
        var secret = McpCredentialSecret.bearer("token-value");
        assertThat(secret.toString()).doesNotContain("token-value");
        assertThatThrownBy(() -> service.update(c.id(), "Bearer", URI.create("https://example.org"), McpAuthType.BEARER, McpProjectAccess.all(), McpCredentialChange.REPLACE, McpCredentialSecret.bearer("****"), null, null)).isInstanceOf(IllegalArgumentException.class);
        var configured = service.update(c.id(), "Bearer", URI.create("https://example.org"), McpAuthType.BEARER, McpProjectAccess.all(), McpCredentialChange.REPLACE, secret, null, null);
        assertThat(configured.credentialConfigured()).isTrue();
        assertThat(configured.toString()).doesNotContain("token-value");
        service.update(c.id(), "Bearer", URI.create("https://example.org"), McpAuthType.BEARER, McpProjectAccess.all(), McpCredentialChange.KEEP, null, null, null);
        assertThat(service.get(c.id()).credentialConfigured()).isTrue();
        service.update(c.id(), "Bearer", URI.create("https://example.org"), McpAuthType.BEARER, McpProjectAccess.all(), McpCredentialChange.REMOVE, null, null, null);
        assertThat(service.get(c.id()).credentialConfigured()).isFalse();
    }

    @Test void rejectsUnsafeEndpointAndHeaders() {
        assertThatThrownBy(() -> service.create("x", URI.create("https://u:p@example.org"), McpAuthType.NONE, McpProjectAccess.all(), null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("x", URI.create("https://example.org/?token=x"), McpAuthType.NONE, McpProjectAccess.all(), null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpCredentialSecret.headers(Map.of("Host", "evil"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpCredentialSecret.headers(Map.of("X-Key", "a\r\nb"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void endpointChangeCannotKeepExistingCredential() {
        var c = service.create("Bearer", URI.create("https://example.org/a"), McpAuthType.BEARER,
                McpProjectAccess.all(), McpCredentialSecret.bearer("synthetic-token"), null, null);
        assertThatThrownBy(() -> service.update(c.id(), "Bearer", URI.create("https://example.org/b"),
                McpAuthType.BEARER, McpProjectAccess.all(), McpCredentialChange.KEEP, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(c.id()).endpoint()).isEqualTo(URI.create("https://example.org/a"));
    }

    @Test void endpointChangeClearsPreviousToolApprovals() {
        var c = service.create("x", URI.create("https://example.org/a"), McpAuthType.NONE,McpProjectAccess.all(),null, null, null);
        metadata.put(c.id(),new McpConnection(c.id(),c.installationId(),c.displayName(),c.endpoint(),c.authType(),
                c.enabled(),c.projectAccess(),Set.of(new McpAllowedTool("old-tool","old-schema")),false,
                c.createdAt(),c.updatedAt(),null,null, null, null));
        var updated = service.update(c.id(), "x", URI.create("https://example.org/b"), McpAuthType.NONE,
                McpProjectAccess.all(),McpCredentialChange.KEEP,null, null, null);
        assertThat(updated.allowedTools()).isEmpty();
    }

    @Test void credentialReplacementClearsPreviousToolApprovals() {
        var c = service.create("x", URI.create("https://example.org/mcp"), McpAuthType.BEARER,
                McpProjectAccess.all(), McpCredentialSecret.bearer("first-synthetic"), null, null);
        metadata.put(c.id(), new McpConnection(c.id(), c.installationId(), c.displayName(), c.endpoint(), c.authType(),
                true, c.projectAccess(), Set.of(new McpAllowedTool("read", "sha256:old")), true,
                c.createdAt(), c.updatedAt(), Instant.now(), null, null, null));
        var replaced = service.update(c.id(), "x", c.endpoint(), McpAuthType.BEARER, McpProjectAccess.all(),
                McpCredentialChange.REPLACE, McpCredentialSecret.bearer("second-synthetic"), null, null);
        assertThat(replaced.allowedTools()).isEmpty();
        assertThat(replaced.checkedAt()).isNull();
    }

    @Test void enabledPolicyRequiresExactProjectToolAndCredential() {
        var c = service.create("x",URI.create("https://example.org"),McpAuthType.BEARER,
                McpProjectAccess.selected(Set.of(project)),McpCredentialSecret.bearer("synthetic"), null, null);
        metadata.put(c.id(),new McpConnection(c.id(),c.installationId(),c.displayName(),c.endpoint(),c.authType(),
                true,c.projectAccess(),Set.of(new McpAllowedTool("read","sha256:one")),true,c.createdAt(),c.updatedAt(),null,null, null, null));
        assertThat(service.allows(c.id(),project,"read","sha256:one")).isTrue();
        assertThat(service.allows(c.id(),project,"read","sha256:two")).isFalse();
        assertThat(service.allows(c.id(),project,"other","sha256:one")).isFalse();
        assertThat(service.allows(c.id(),UUID.randomUUID(),"read","sha256:one")).isFalse();
        projectExists = false;
        assertThat(service.allows(c.id(),project,"read","sha256:one")).isFalse();
        projectExists = true;
        service.setEnabled(c.id(),false);
        assertThat(service.allows(c.id(),project,"read","sha256:one")).isFalse();
        service.setEnabled(c.id(),true);
        service.update(c.id(),"x",c.endpoint(),McpAuthType.BEARER,McpProjectAccess.selected(Set.of()),McpCredentialChange.KEEP,null, null, null);
        assertThat(service.allows(c.id(),project,"read","sha256:one")).isFalse();
        service.update(c.id(),"x",c.endpoint(),McpAuthType.BEARER,McpProjectAccess.all(),McpCredentialChange.REMOVE,null, null, null);
        assertThat(service.allows(c.id(),project,"read","sha256:one")).isFalse();
    }

    @Test void rejectsInvalidCredentialChangeCombinations() {
        var c = service.create("x",URI.create("https://example.org"),McpAuthType.BEARER,McpProjectAccess.all(),McpCredentialSecret.bearer("synthetic"), null, null);
        assertThatThrownBy(() -> service.update(c.id(),"x",c.endpoint(),McpAuthType.BEARER,McpProjectAccess.all(),McpCredentialChange.KEEP,McpCredentialSecret.bearer("other"), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(c.id(),"x",c.endpoint(),McpAuthType.BEARER,McpProjectAccess.all(),McpCredentialChange.REMOVE,McpCredentialSecret.bearer("other"), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(c.id(),"x",c.endpoint(),McpAuthType.BEARER,McpProjectAccess.all(),McpCredentialChange.REPLACE,null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(c.id(),"x",c.endpoint(),McpAuthType.NONE,McpProjectAccess.all(),McpCredentialChange.REPLACE,McpCredentialSecret.bearer("other"), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(c.id(),"x",c.endpoint(),McpAuthType.SECRET_HEADERS,McpProjectAccess.all(),McpCredentialChange.KEEP,null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("x",c.endpoint(),McpAuthType.NONE,McpProjectAccess.all(),McpCredentialSecret.bearer("other"), null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentEndpointReplacementCannotBeUndoneBySetEnabled() {
        var c = service.create("x",URI.create("https://example.org/a"),McpAuthType.BEARER,McpProjectAccess.all(),McpCredentialSecret.bearer("a"), null, null);
        onMutationRead = () -> service.update(c.id(),"x",URI.create("https://example.org/b"),McpAuthType.BEARER,
                McpProjectAccess.selected(Set.of(project)),McpCredentialChange.REPLACE,McpCredentialSecret.bearer("b"), null, null);
        service.setEnabled(c.id(),true);
        assertThat(service.get(c.id()).endpoint()).isEqualTo(URI.create("https://example.org/b"));
        assertThat(service.get(c.id()).projectAccess()).isEqualTo(McpProjectAccess.selected(Set.of(project)));
    }

    @Test void staleMutationCannotResurrectDeletedConnection() {
        var c = service.create("x",URI.create("https://example.org/a"),McpAuthType.BEARER,McpProjectAccess.all(),McpCredentialSecret.bearer("a"), null, null);
        onMutationRead = () -> service.remove(c.id());
        assertThatThrownBy(() -> service.setEnabled(c.id(),true)).isInstanceOf(NoSuchElementException.class);
        assertThat(repository.findById(installation,c.id())).isEmpty();
    }
}
