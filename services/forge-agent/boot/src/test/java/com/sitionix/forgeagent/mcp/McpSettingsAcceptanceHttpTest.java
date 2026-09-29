package com.sitionix.forgeagent.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.ForgeAgentApplication;
import com.sitionix.forgeagent.application.mcp.McpConnectionService;
import com.sitionix.forgeagent.application.mcp.McpGatewayService;
import com.sitionix.forgeagent.application.mcp.McpExecutionSelectionService;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.application.mcp.McpGatewayAccessException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryVerifier;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.PostgresMcpConnectionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.PostgresForgeInstanceIdentityRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

/** Actual browser → Nexus → Agent → PostgreSQL → SDK gateway/native CLI.
 * Execution lease and privileged OS verification are explicit substitutes;
 * this fixture does not claim normal worker/systemd or full Forge restart acceptance.
 */
@EnabledIfSystemProperty(named="forge.codex.stage5-e2e",matches="true")
@SpringBootTest(classes=ForgeAgentApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.address=127.0.0.1","forge.agent.worker.scheduling-enabled=false"})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class McpSettingsAcceptanceHttpTest {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    static Path directory;
    static HttpServer upstream;
    static int toolCalls;
    static String lastTool;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        DB.start();directory=Files.createTempDirectory("mcp-settings-acceptance-");

        protectedFile("key","active=k1\nkey.k1="+Base64.getEncoder().encodeToString(new byte[32])+"\n");
        protectedFile("db",DB.getPassword());
        registry.add("spring.datasource.url",DB::getJdbcUrl);registry.add("spring.datasource.username",DB::getUsername);
        registry.add("forge.mcp.key-file",()->directory.resolve("key").toString());
        registry.add("forge.mcp.database-credential-file",()->directory.resolve("db").toString());
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/mcp",exchange->{
            try {
                if(!exchange.getRequestMethod().equals("POST")) {exchange.sendResponseHeaders(405,-1);return;}
                var json=new ObjectMapper();var body=json.readTree(exchange.getRequestBody());
                if(!body.has("id")) {exchange.sendResponseHeaders(202,-1);return;}
                Object result=switch(body.path("method").asText()) {
                    case "initialize" -> Map.of("protocolVersion","2025-11-25","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","echo-fixture","version","1"));
                    case "tools/list" -> Map.of("tools",List.of(
                        Map.of("name","echo","description","Read-only fixture echo","inputSchema",
                            Map.of("type","object","properties",Map.of("text",Map.of("type","string")))),
                        Map.of("name","inspect","description","Read-only fixture inspect","inputSchema",
                            Map.of("type","object","properties",Map.of()))));
                    case "tools/call" -> {toolCalls++;lastTool=body.path("params").path("name").asText();yield Map.of("content",List.of(Map.of("type","text","text","fixture:read-only")),"isError",false);}
                    default -> throw new IllegalArgumentException("Unknown fixture method");
                };
                byte[] response=json.writeValueAsBytes(Map.of("jsonrpc","2.0","id",body.get("id"),"result",result));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            } finally {exchange.close();}
        });upstream.start();
        registry.add("forge.mcp.probe.allowed-private-endpoints",()->"127.0.0.1:"+upstream.getAddress().getPort());
    }
    static void protectedFile(String name,String value) throws Exception {
        Path file=directory.resolve(name);Files.writeString(file,value);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
    }
    @AfterAll static void cleanup() throws Exception {
        if(upstream!=null)upstream.stop(0);DB.stop();
        if(directory!=null)try(var files=Files.walk(directory)) {for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    @LocalServerPort int agentPort;
    @MockBean RuntimeBoundaryVerifier verifier;
    @MockBean AgentExecutionSessionRepository sessions;
    @MockBean NodeRunRepository nodes;
    @MockBean WorkflowRunRepository workflows;
    @Autowired ProjectRepository projects;
    @Autowired McpConnectionService connections;
    @Autowired McpGatewayService runtime;
    @Autowired McpExecutionSelectionService selection;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;

    @Test void browserPermissionsStayDisabledUntilExplicitEnableAndUseConfirmedPolicy(CapturedOutput output) throws Exception {
        Instant now=Instant.now();UUID projectId=UUID.randomUUID();
        projects.save(new Project(projectId,"Stage 5 Fixture","stage 5 fixture",now,now));
        Path root=repositoryRoot();Path nexusJar=root.resolve("services/forge-nexus/boot/target/boot-0.0.1-SNAPSHOT.jar");
        assertThat(nexusJar).isRegularFile();
        int nexusPort;try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {nexusPort=socket.getLocalPort();}
        String origin="http://127.0.0.1:"+nexusPort;
        Process nexus=startNexus(nexusJar,origin,nexusPort);
        try {
            awaitNexus(nexus,origin);
            String browser=browser(root,origin,"connect",null);
            assertThat(browser).contains("SETTINGS_BROWSER_ACTUAL_NEXUS_PASS");
            var connection=connections.list().getFirst();UUID id=connection.id();
            assertThat(connection.enabled()).isTrue();assertThat(connection.allowedTools()).extracting(McpAllowedTool::name).containsExactly("echo");
            assertThat(connection.projectAccess().projectIds()).containsExactly(projectId);
            assertThat(toolCalls).isZero();
            var reloaded=new PostgresMcpConnectionRepository(jdbc,transactions, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()).findById(
                    new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate(),id).orElseThrow();
            assertThat(reloaded).isEqualTo(connection);
            var claim=trustedLease(projectId,now);
            var first=runtime.issue(claim,now.plusSeconds(180),id);
            var second=runtime.issue(claim,now.plusSeconds(180),id);
            assertThat(first.token()).isNotEqualTo(second.token());
            ProcessBuilder nativeCall=new ProcessBuilder("python3",root.resolve("scripts/runtime/tests/stage5_codex_fixture.py").toString());
            nativeCall.environment().put("FORGE_STAGE4_GATEWAY_BASE","http://127.0.0.1:"+agentPort);
            nativeCall.environment().put("FORGE_STAGE5_CONNECTION_ID",id.toString());
            nativeCall.environment().put("FORGE_STAGE5_GRANT_A",first.token());nativeCall.environment().put("FORGE_STAGE5_GRANT_B",second.token());
            assertThat(run(nativeCall,Duration.ofMinutes(3))).contains("STAGE5_NATIVE_GATEWAY_PASS").doesNotContain(first.token(),second.token());
            assertThat(toolCalls).isEqualTo(2);
            UUID otherProject=UUID.randomUUID();
            projects.save(new Project(otherProject,"Other Fixture","other fixture",now,now));
            var otherClaim=trustedLease(otherProject,now);
            assertThatThrownBy(()->runtime.issue(otherClaim,now.plusSeconds(180),id)).isInstanceOf(McpGatewayAccessException.class);
            UUID unrelatedWorkflow=sessions.findSession(otherClaim.sessionId()).orElseThrow().workflowRunId();
            var withoutMcp=selection.prepare(new NodeExecutionClaim(unrelatedWorkflow,otherClaim.nodeRunId(),null,"read-only","fixture","fixture",null,null,null,List.of(),null),now.plusSeconds(180));
            assertThat(withoutMcp.selection().entries()).isEmpty();
            assertThat(toolCalls).isEqualTo(2);
            // Restart Nexus, retaining the real Agent/PostgreSQL state; not a full Forge restart.
            stop(nexus);nexus=startNexus(nexusJar,origin,nexusPort);awaitNexus(nexus,origin);
            String disabled=browser(root,origin,"disable",id);
            assertThat(disabled).contains("SETTINGS_DISABLE_CONFIRMED","SETTINGS_BROWSER_ACTUAL_NEXUS_PASS");
            assertThat(connections.get(id).enabled()).isFalse();
            assertThatThrownBy(()->runtime.authorize(first.token(),id)).isInstanceOf(McpGatewayAccessException.class);
            try(var http=HttpClient.newHttpClient()) {
                var denied=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+agentPort+"/internal/mcp/connections/"+id))
                    .header("Authorization","Bearer "+second.token()).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{}}}")).build(),HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).isEqualTo(401);assertThat(denied.body()).doesNotContain(second.token());
            }
            assertThat(toolCalls).isEqualTo(2);
            String changed=browser(root,origin,"permissions",id,otherProject);
            assertThat(changed).contains("SETTINGS_PERMISSIONS_DISABLED_CONFIRMED","SETTINGS_BROWSER_ACTUAL_NEXUS_PASS");
            var savedPolicy=connections.get(id);
            assertThat(savedPolicy.enabled()).isFalse();
            assertThat(savedPolicy.projectAccess().projectIds()).containsExactly(otherProject);
            assertThat(savedPolicy.allowedTools()).extracting(McpAllowedTool::name).containsExactly("inspect");
            assertThatThrownBy(()->runtime.issue(claim,Instant.now().plusSeconds(180),id)).isInstanceOf(McpGatewayAccessException.class);
            assertThatThrownBy(()->runtime.issue(otherClaim,Instant.now().plusSeconds(180),id)).isInstanceOf(McpGatewayAccessException.class);
            assertThatThrownBy(()->runtime.call(second.token(),id,"echo",connection.allowedTools().iterator().next().schemaFingerprint(),"{}"))
                .isInstanceOf(McpGatewayAccessException.class);
            assertThat(toolCalls).isEqualTo(2);
            assertThat(browser(root,origin,"enable",id)).contains("SETTINGS_ENABLE_CONFIRMED","SETTINGS_BROWSER_ACTUAL_NEXUS_PASS");
            assertThat(connections.get(id).enabled()).isTrue();
            assertThatThrownBy(()->runtime.issue(claim,Instant.now().plusSeconds(180),id)).isInstanceOf(McpGatewayAccessException.class);
            var next=runtime.issue(otherClaim,Instant.now().plusSeconds(180),id);
            var grant=runtime.authorize(next.token(),id);
            assertThat(grant.projectId()).isEqualTo(otherProject);assertThat(grant.tools()).isEqualTo(savedPolicy.allowedTools());
            assertThatThrownBy(()->runtime.call(next.token(),id,"echo",connection.allowedTools().iterator().next().schemaFingerprint(),"{}"))
                .isInstanceOf(McpGatewayAccessException.class);
            assertThat(toolCalls).isEqualTo(2);
            var inspect=savedPolicy.allowedTools().iterator().next();
            assertThat(runtime.call(next.token(),id,inspect.name(),inspect.schemaFingerprint(),"{}").isError()).isFalse();
            assertThat(toolCalls).isEqualTo(3);assertThat(lastTool).isEqualTo("inspect");
            assertThat(output.getAll()).doesNotContain(next.token());
            assertThat(Files.readString(directory.resolve("nexus.log"))).doesNotContain(next.token());
            assertThat(output.getAll()).doesNotContain(first.token(),second.token());
            assertThat(Files.readString(directory.resolve("nexus.log"))).doesNotContain(first.token(),second.token());
        } finally {stop(nexus);}
    }
    private AgentSessionExecutionClaim trustedLease(UUID projectId,Instant now) {
        UUID sessionId=UUID.randomUUID(),turnId=UUID.randomUUID(),nodeId=UUID.randomUUID(),workflowId=UUID.randomUUID();
        var session=new AgentExecutionSession(sessionId,workflowId,UUID.randomUUID(),UUID.randomUUID(),null,"codex",null,null,NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE,
            AgentExecutionSessionStatus.ACTIVE,null,nodeId,"stage5-fixture",1L,now.plusSeconds(240),null,null,now,now,null,null);
        var turn=new AgentExecutionTurn(turnId,sessionId,nodeId,null,1,AgentExecutionTurnStatus.ACTIVE,null,null,null,null,null,now,null,now,now);
        when(sessions.findSession(sessionId)).thenReturn(Optional.of(session));when(sessions.findByNodeRunId(nodeId)).thenReturn(Optional.of(new AgentExecutionAllocation(session,turn)));
        when(sessions.lockCurrentLease(sessionId,"stage5-fixture",1L)).thenReturn(true);
        when(nodes.findById(nodeId)).thenReturn(Optional.of(new NodeRun(nodeId,workflowId,UUID.randomUUID(),UUID.randomUUID(),"fixture","read-only",null,NodeInputMode.DEPENDENCIES_ONLY,
            new NodePosition(1,1),UUID.randomUUID(),null,null,null,null,NodeRunStatus.RUNNING,null,null,null,now,now,null,null)));
        when(workflows.findById(workflowId)).thenReturn(Optional.of(new WorkflowRun(workflowId,projectId,UUID.randomUUID(),null,"fixture","read-only",WorkflowRunStatus.RUNNING,
            List.of(),List.of(),List.of(),null,null,null,now,now,null,List.of())));
        return new AgentSessionExecutionClaim(sessionId,turnId,nodeId,"stage5-fixture",1L,now.plusSeconds(240),null,"codex");
    }
    private Process startNexus(Path jar,String origin,int port) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),
            "--server.address=127.0.0.1","--server.port="+port,"--spring.config.import=","--spring.docker.compose.enabled=false",
            "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "--forge.ai.infrastructure.agent.base-url=http://127.0.0.1:"+agentPort)
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("nexus.log").toFile()).start();
    }
    private static void awaitNexus(Process process,String origin) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        try(var http=HttpClient.newHttpClient()) {
            while(System.nanoTime()<deadline && process.isAlive()) {
                try {if(http.send(HttpRequest.newBuilder(URI.create(origin+"/fgaisox/actuator/health")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;}
                catch(java.io.IOException ignored) { }
                Thread.sleep(100);
            }
        }
        throw new AssertionError("Disposable Nexus did not become ready; inspect private fixture log");
    }
    private String browser(Path root,String origin,String action,UUID id) throws Exception {
        return browser(root,origin,action,id,null);
    }
    private String browser(Path root,String origin,String action,UUID id,UUID projectId) throws Exception {
        var builder=new ProcessBuilder("node",root.resolve("services/forge-console/scripts/mcp-settings-browser-smoke.mjs").toString());
        builder.environment().put("FORGE_SETTINGS_BASE_URL",origin+"/fgaisox");
        builder.environment().put("FORGE_SETTINGS_MCP_ENDPOINT","http://127.0.0.1:"+upstream.getAddress().getPort()+"/mcp");
        builder.environment().put("FORGE_SETTINGS_ACTION",action);if(id!=null)builder.environment().put("FORGE_SETTINGS_CONNECTION_ID",id.toString());
        if(projectId!=null) {
            builder.environment().put("FORGE_SETTINGS_PROJECT_ID",projectId.toString());
            builder.environment().put("FORGE_SETTINGS_TOOL_NAME","inspect");
        }
        return run(builder,Duration.ofSeconds(45));
    }
    private static String run(ProcessBuilder builder,Duration budget) throws Exception {
        Process process=builder.redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(budget.toMillis(),TimeUnit.MILLISECONDS)).isTrue();
            String result=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(result).isZero();return result;
        } finally {stop(process);}
    }
    private static void stop(Process process) throws Exception {
        process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
    }
    private static Path repositoryRoot() {
        for(Path path=Path.of("").toAbsolutePath();path!=null;path=path.getParent())if(Files.isDirectory(path.resolve("services/forge-console")))return path;
        throw new IllegalStateException("Repository fixture is unavailable");
    }
}
