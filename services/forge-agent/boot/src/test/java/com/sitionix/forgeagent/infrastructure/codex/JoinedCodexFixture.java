package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.sitionix.forgeagent.api.llm.*;
import com.sitionix.forgeagent.application.llm.*;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.domain.exception.LlmAuthorizationException;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real services/clients/HTTP filters around a synthetic file account and in-memory JSON-RPC peer. */
final class JoinedCodexFixture implements AutoCloseable {
    static final String LIFECYCLE = "/api/v1/integrations/llm";
    private static final String TOKEN = "synthetic_service_token_for_joined_fixture_only";
    final Path profile;
    final AtomicInteger processStarts = new AtomicInteger();
    final List<String> refreshedAccounts = new CopyOnWriteArrayList<>();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final LlmAuthorizationService authorization;
    final ForgeCodexAuthorizationGate gate;
    final ForgeCodexOperationsAdapter operations;
    final CodexAppServerClient client;
    final MockMvc mvc;
    private final List<LlmAuthorizationGateway.Event> events = new ArrayList<>();

    JoinedCodexFixture(Path profile) throws IOException {
        this.profile = Files.createDirectories(profile);
        Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwx------"));
        Path serviceToken = profile.resolve("service-token.fixture");
        Files.writeString(serviceToken, TOKEN);
        Files.setPosixFilePermissions(serviceToken, PosixFilePermissions.fromString("rw-------"));
        authorization = new LlmAuthorizationService(this::session, Clock.systemUTC(), new FileCodexAuthorizationFence(profile));
        gate = new ForgeCodexAuthorizationGate(authorization);
        var properties = new CodexAppServerProperties();
        properties.setRuntimeCwd(profile.toString());
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setTurnTimeout(Duration.ofSeconds(3));
        CodexAppServerProcessStarter starter = cwd -> {
            processStarts.incrementAndGet();
            try { return new StartedCodexAppServer(new SyntheticProcess(), List.of("synthetic-only"), Instant.now()); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        };
        var workspace = new CodexRuntimeWorkspace(properties);
        operations = new ForgeCodexOperationsAdapter(mapper, starter, properties, workspace, gate, Clock.systemUTC());
        client = new CodexAppServerClient(mapper, starter, properties, workspace, null, gate);
        mvc = MockMvcBuilders.standaloneSetup(new LlmAuthorizationController(authorization),
                        new ForgeCodexInternalController(operations, mapper))
                .setControllerAdvice(new LlmAuthorizationExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new LlmAuthorizationRequestFilter(mapper), new ForgeCodexServiceAuthFilter(serviceToken.toString()))
                .build();
    }

    private LlmAuthorizationGateway.Session session() {
        return new LlmAuthorizationGateway.Session() {
            private boolean open = true;
            public LlmAuthorizationGateway.Account readAccount(boolean refresh) {
                try {
                    String email = Files.exists(profile.resolve("account.fixture"))
                            ? Files.readString(profile.resolve("account.fixture")) : null;
                    if (refresh && email != null) refreshedAccounts.add(email);
                    return new LlmAuthorizationGateway.Account(email != null, email, email == null ? null : "plus");
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
            public LlmAuthorizationGateway.Login startLogin() {
                return new LlmAuthorizationGateway.Login("synthetic-login", "https://auth.openai.com/authorize?state=fixture");
            }
            public void cancelLogin(String id) { }
            public void logout() {
                try { Files.deleteIfExists(profile.resolve("account.fixture")); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
            public List<LlmAuthorizationGateway.Event> drainEvents() {
                var result = List.copyOf(events); events.clear(); return result;
            }
            public boolean healthy() { return open; }
            public void close() { open = false; }
        };
    }

    String bearer() { return "Bearer " + TOKEN; }

    void loginThroughController() throws Exception {
        var response = mvc.perform(post(LIFECYCLE + "/codex/login").contentType("application/json")
                        .content("{\"browserBinding\":\"synthetic-browser\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        String id = mapper.readTree(response.getResponse().getContentAsString()).path("loginId").asText();
        Files.writeString(profile.resolve("account.fixture"), "forge@example.test");
        events.add(LlmAuthorizationGateway.Event.completed("synthetic-login", true));
        mvc.perform(get(LIFECYCLE + "/codex/logins/" + id).header("X-Forge-Browser-Binding", "synthetic-browser"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.authUrl").isEmpty());
        mvc.perform(get(LIFECYCLE + "/providers")).andExpect(jsonPath("$[0].authState").value("CONNECTED"));
    }

    void assertKnowledgeGenerationCompletes() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/internal/v1/codex/generations").header("Authorization", bearer())
                        .contentType("application/json").content(generation(id)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.requestId").value(id.toString()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (operations.get(id).status().equals("running") && System.nanoTime() < deadline) Thread.sleep(5);
        mvc.perform(get("/internal/v1/codex/generations/" + id).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.rawText").value("synthetic-answer"))
                .andExpect(jsonPath("$.serverVersion").value("0.160.0"));
    }

    void assertInferenceDenied() throws Exception { assertInferenceDenied("CODEX_AUTH_REQUIRED"); }

    void assertInferenceDenied(String expectedCode) throws Exception {
        var request = new CodexTurnRequest("input", "instructions", "synthetic-model", null,
                mapper.createObjectNode(), new ExecutionWorkspace(profile, List.of(profile)));
        var callbacks = new CodexExecutionIdentityCallbacks() {
            public void conversationStarted(String id, String version) { fail("Signed-out conversation started"); }
            public void turnStarted(String id) { fail("Signed-out turn started"); }
        };
        var grants = new com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants(
                Map.of("forge_0123456789ab4cde80123456789abcde", "synthetic-grant"));
        // Retry is another call through the same public entry point, never a cached lease.
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> paths = List.of(
                () -> client.execute(request), () -> client.execute(request),
                () -> client.execute(request, grants),
                () -> client.executeTrackedFresh(request, callbacks),
                () -> client.executeTrackedFresh(request, grants, callbacks),
                () -> client.executeDurable(request, null, callbacks),
                () -> client.executeDurable(request, "persisted-thread", "0.160.0", callbacks),
                () -> client.executeDurable(request, "persisted-thread", "0.160.0", grants, callbacks),
                () -> client.request("turn/start", mapper.createObjectNode()));
        for (var path : paths) assertThatThrownBy(path).satisfies(failure ->
                assertThat(LlmAuthorizationException.find(failure).code()).isIn("CODEX_AUTH_REQUIRED", expectedCode));
        mvc.perform(post("/internal/v1/codex/generations").header("Authorization", bearer())
                        .contentType("application/json").content(generation(UUID.randomUUID())))
                .andExpect(status().is("CODEX_AUTH_REQUIRED".equals(expectedCode) ? 409 : 503)).andExpect(jsonPath("$.code").value(expectedCode));
        mvc.perform(get("/internal/v1/codex/usage").header("Authorization", bearer()))
                .andExpect(status().is("CODEX_AUTH_REQUIRED".equals(expectedCode) ? 409 : 503)).andExpect(jsonPath("$.code").value(expectedCode));
    }

    private String generation(UUID id) throws IOException {
        var value = mapper.createObjectNode().put("requestId", id.toString()).put("prompt", "input")
                .put("modelId", "synthetic-model").putNull("effortId").put("responseMode", "text").put("timeoutSeconds", 3);
        return mapper.writeValueAsString(value);
    }

    @Override public void close() { client.close(); operations.close(); authorization.close(); }

    private final class SyntheticProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream(65536);
        private final PipedOutputStream peer = new PipedOutputStream(stdout);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final OutputStream stdin = new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();
            public synchronized void write(int value) throws IOException {
                if (value == '\n') { receive(mapper.readTree(line.toByteArray())); line.reset(); }
                else line.write(value);
            }
        };
        SyntheticProcess() throws IOException { }
        private void receive(JsonNode request) throws IOException {
            if (!request.has("id")) return;
            String method = request.path("method").asText();
            String result = switch (method) {
                case "initialize" -> "{\"userAgent\":\"codex/0.160.0\"}";
                case "thread/start" -> "{\"thread\":{\"id\":\"synthetic-thread\"}}";
                case "turn/start" -> "{\"turn\":{\"id\":\"synthetic-turn\"}}";
                case "model/list" -> "{\"data\":[{\"id\":\"synthetic-model\"}],\"nextCursor\":null}";
                case "account/rateLimits/read" -> "{\"rateLimits\":{}}";
                default -> throw new IOException("Unexpected synthetic protocol method: " + method);
            };
            send("{\"id\":" + request.get("id") + ",\"result\":" + result + "}");
            if (method.equals("turn/start")) {
                send("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"synthetic-thread\",\"turnId\":\"synthetic-turn\",\"item\":{\"type\":\"agentMessage\",\"text\":\"synthetic-answer\"}}}");
                send("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"synthetic-thread\",\"turn\":{\"id\":\"synthetic-turn\",\"status\":\"completed\"}}}");
            }
        }
        private void send(String frame) throws IOException { peer.write((frame + "\n").getBytes(StandardCharsets.UTF_8)); peer.flush(); }
        public OutputStream getOutputStream() { return stdin; }
        public InputStream getInputStream() { return stdout; }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() throws InterruptedException { exited.await(); return 0; }
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return exited.await(timeout, unit); }
        public int exitValue() { if (isAlive()) throw new IllegalThreadStateException(); return 0; }
        public boolean isAlive() { return exited.getCount() != 0; }
        public long pid() { return 900_000L; }
        public void destroy() { try { peer.close(); } catch (IOException ignored) { } exited.countDown(); }
        public Process destroyForcibly() { destroy(); return this; }
    }
}
