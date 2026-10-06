package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Joined real Agent components; synthetic provider/account, never a host login or restart. */
class JoinedCodexAuthorizationTest {
    @TempDir Path root;

    @Test void terminalProfileDoesNotAuthorizeAnyAgentOrKnowledgeInferencePath() throws Exception {
        Files.createDirectories(root.resolve("personal/.codex"));
        Files.writeString(root.resolve("personal/.codex/auth.json"), "synthetic-terminal-account");
        try (var forge = new JoinedCodexFixture(root.resolve("forge"))) {
            forge.mvc.perform(get(JoinedCodexFixture.LIFECYCLE + "/providers"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].authState").value("SIGNED_OUT"));
            forge.assertInferenceDenied();
            assertThat(forge.processStarts.get()).isZero();
        }
        assertThat(Files.readString(root.resolve("personal/.codex/auth.json"))).isEqualTo("synthetic-terminal-account");
    }

    @Test void loginSurvivesFixtureReconstructionAndKnowledgeSharesFreshAccountAuthority() throws Exception {
        Path profile = root.resolve("forge");
        try (var forge = new JoinedCodexFixture(profile)) {
            forge.loginThroughController();
            forge.assertKnowledgeGenerationCompletes();
            assertThat(forge.refreshedAccounts).containsOnly("forge@example.test").isNotEmpty();
        }
        byte[] persisted = Files.readAllBytes(profile.resolve("account.fixture"));
        try (var restarted = new JoinedCodexFixture(profile)) {
            restarted.mvc.perform(get(JoinedCodexFixture.LIFECYCLE + "/providers"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].email").value("forge@example.test"));
            restarted.assertKnowledgeGenerationCompletes();
            restarted.mvc.perform(get("/internal/v1/codex/usage").header("Authorization", restarted.bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.rateLimits").exists());
            assertThat(restarted.refreshedAccounts).containsOnly("forge@example.test").hasSizeGreaterThanOrEqualTo(2);
        }
        assertThat(Files.readAllBytes(profile.resolve("account.fixture"))).isEqualTo(persisted);
    }

    @Test void logoutThroughControllerRevokesEveryInferencePathButKeepsCatalogObservation() throws Exception {
        try (var forge = new JoinedCodexFixture(root.resolve("forge"))) {
            forge.loginThroughController();
            forge.assertKnowledgeGenerationCompletes();
            forge.mvc.perform(post(JoinedCodexFixture.LIFECYCLE + "/codex/logout")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.authState").value("SIGNED_OUT"));
            int before = forge.processStarts.get();
            forge.assertInferenceDenied();
            assertThat(forge.processStarts.get()).isEqualTo(before);
            forge.mvc.perform(get("/internal/v1/codex/models").header("Authorization", forge.bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("synthetic-model"));
            assertThat(Files.exists(forge.profile.resolve("account.fixture"))).isFalse();
        }
    }

    @Test void connectedProviderDoesNotReplaceKnowledgeServiceCredentialOrBrowserGuards() throws Exception {
        try (var forge = new JoinedCodexFixture(root.resolve("forge"))) {
            forge.loginThroughController();
            forge.mvc.perform(get("/internal/v1/codex/usage")).andExpect(status().isUnauthorized());
            forge.mvc.perform(get("/internal/v1/codex/usage").header("Authorization", forge.bearer())
                            .header("Origin", "http://127.0.0.1:9099"))
                    .andExpect(status().isForbidden());
            assertThat(forge.processStarts.get()).isZero();
        }
    }

    @Test void staleConnectedDisplayCannotAuthorizeAfterProviderAccountDisappears() throws Exception {
        try (var forge = new JoinedCodexFixture(root.resolve("forge"))) {
            forge.loginThroughController();
            Files.delete(forge.profile.resolve("account.fixture"));
            forge.assertInferenceDenied();
            assertThat(forge.processStarts.get()).isZero();
        }
    }

    @Test void cancelledCallbackAccountCannotAuthorizeAfterFixtureReconstruction() throws Exception {
        Path profile = root.resolve("forge");
        try (var forge = new JoinedCodexFixture(profile)) {
            var attempt = forge.authorization.startLogin("owner");
            Files.writeString(profile.resolve("account.fixture"), "late@example.test");
            forge.authorization.cancelLogin(attempt.loginId(), "owner");
        }
        try (var restarted = new JoinedCodexFixture(profile)) {
            restarted.assertInferenceDenied("CODEX_LOGOUT_REQUIRED");
            assertThat(restarted.processStarts.get()).isZero();
            assertThat(restarted.authorization.readAccount().errorCode()).isEqualTo("CODEX_LOGOUT_REQUIRED");
            restarted.mvc.perform(post(JoinedCodexFixture.LIFECYCLE + "/codex/logout")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.authState").value("SIGNED_OUT"));
            restarted.loginThroughController();
            restarted.assertKnowledgeGenerationCompletes();
        }
    }

    @Test void preexistingAccountWithoutForgeApprovalIsNeverAdopted() throws Exception {
        Path profile = Files.createDirectories(root.resolve("forge"));
        Files.writeString(profile.resolve("account.fixture"), "unapproved@example.test");
        try (var forge = new JoinedCodexFixture(profile)) {
            forge.assertInferenceDenied();
            assertThat(forge.processStarts.get()).isZero();
            forge.loginThroughController();
            forge.assertKnowledgeGenerationCompletes();
        }
    }
}
