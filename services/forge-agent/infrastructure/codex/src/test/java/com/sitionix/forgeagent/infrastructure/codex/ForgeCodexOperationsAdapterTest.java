package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.*;
import com.sitionix.forgeagent.domain.model.ForgeCodexGenerationRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ForgeCodexOperationsAdapterTest {
    @TempDir Path root;
    ObjectMapper mapper=new ObjectMapper();
    ForgeCodexGenerationRequest request(UUID id){return new ForgeCodexGenerationRequest(id,"input","m","low","json_object",5);}
    ForgeCodexOperationsAdapter adapter(FakeCodexProcess process,ForgeAuthorizationFixture auth) {
        return adapter(process,auth,Clock.systemUTC());
    }
    ForgeCodexOperationsAdapter adapter(FakeCodexProcess process,ForgeAuthorizationFixture auth,Clock clock) {
        var properties=new CodexAppServerProperties();properties.setRuntimeCwd(root.toString());
        CodexAppServerProcessStarter starter=directory -> new StartedCodexAppServer(process,List.of("synthetic"),Instant.now());
        return new ForgeCodexOperationsAdapter(mapper,starter,properties,new CodexRuntimeWorkspace(properties),auth.gate,clock);
    }
    JsonNode read(FakeCodexProcess process)throws Exception{return mapper.readTree(process.readRequest());}
    void respond(FakeCodexProcess process,JsonNode request,String result){process.writeStdout("{\"id\":\""+request.path("id").asText()+"\",\"result\":"+result+"}");}
    void initialize(FakeCodexProcess process)throws Exception {
        initialize(process,"0.160.0");
    }
    void initialize(FakeCodexProcess process,String version)throws Exception {
        respond(process,read(process),"{\"userAgent\":\"codex/"+version+"\"}");assertThat(read(process).path("method").asText()).isEqualTo("initialized");
    }
    @Test void signed_out_never_starts_process() {
        var process=new FakeCodexProcess();try(var adapter=adapter(process,new ForgeAuthorizationFixture(false))){
            assertThatThrownBy(()->adapter.submit(request(UUID.randomUUID()))).hasMessageContaining("CODEX_AUTH_REQUIRED");
            assertThat(process.pendingClientRequestBytes()).isZero();
        }finally{process.destroy();}
    }
    @Test void uncharacterized_versions_close_transport_before_model_or_turn_requests()throws Exception {
        var process=new FakeCodexProcess();try(var adapter=adapter(process,new ForgeAuthorizationFixture(true))){
            var result=CompletableFuture.supplyAsync(()->adapter.models(null,1,false));
            respond(process,read(process),"{\"userAgent\":\"codex/0.161.0\"}");
            assertThatThrownBy(()->result.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("CODEX_VERSION_UNSUPPORTED");
            assertThat(process.isAlive()).isFalse();assertThat(process.pendingClientRequestBytes()).isZero();
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"0.160.0","0.160.1"})
    void generation_is_tool_free_and_preserves_schema_metadata_and_duplicate_identity(String version)throws Exception {
        var process=new FakeCodexProcess();try(var adapter=adapter(process,new ForgeAuthorizationFixture(true))){
            UUID id=UUID.randomUUID();adapter.submit(request(id));
            assertThatThrownBy(()->adapter.submit(request(id))).isInstanceOf(IllegalStateException.class);
            initialize(process,version);
            var thread=read(process);var params=thread.path("params");
            assertThat(params.path("environments").size()).isZero();assertThat(params.path("sandbox").asText()).isEqualTo("read-only");
            assertThat(params.path("config").path("features").path("shell_tool").booleanValue()).isFalse();
            assertThat(params.path("config").has("mcp_servers")).isFalse();
            respond(process,thread,"{\"thread\":{\"id\":\"thread\"}}");
            var turn=read(process);
            assertThat(turn.path("params").path("outputSchema").path("required").get(0).asText()).isEqualTo("json");
            respond(process,turn,"{\"turn\":{\"id\":\"turn\"}}");
            process.writeStdout("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread\",\"turnId\":\"turn\",\"item\":{\"type\":\"agentMessage\",\"text\":\"wrapped\"}}}");
            process.writeStdout("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread\",\"turn\":{\"id\":\"turn\",\"status\":\"completed\",\"tokenUsage\":{\"total\":4},\"warnings\":[\"warning\"],\"model\":\"resolved\"}}}");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(adapter.get(id).status().equals("running") && System.nanoTime()<deadline)Thread.sleep(5);
            var result=adapter.get(id);assertThat(result.status()).isEqualTo("completed");assertThat(result.rawText()).isEqualTo("wrapped");
            assertThat(result.serverVersion()).isEqualTo(version);
            assertThat(result.tokenUsage()).containsEntry("total",4);assertThat(result.modelMetadata()).containsEntry("model","resolved");
            assertThat(process.isAlive()).isFalse();
        }
    }
    @Test void cancellation_and_logout_own_initializing_process()throws Exception {
        var process=new FakeCodexProcess();var auth=new ForgeAuthorizationFixture(true);
        try(var adapter=adapter(process,auth)){
            UUID id=adapter.submit(request(UUID.randomUUID()));read(process);
            adapter.cancel(id);adapter.cancel(id);
            assertThat(process.isAlive()).isFalse();assertThat(adapter.get(id).status()).isEqualTo("cancelled");
            auth.service.logout();
        }
    }
    @Test void delete_before_submission_prevents_late_or_retried_generation() {
        var process=new FakeCodexProcess();try(var adapter=adapter(process,new ForgeAuthorizationFixture(true))){
            UUID id=UUID.randomUUID();adapter.cancel(id);adapter.cancel(id);
            assertThatThrownBy(()->adapter.submit(request(id))).isInstanceOf(IllegalStateException.class);
            assertThat(process.pendingClientRequestBytes()).isZero();
        }finally{process.destroy();}
    }
    @Test void server_tool_request_fails_closed_and_closes_owned_process()throws Exception {
        var process=new FakeCodexProcess();try(var adapter=adapter(process,new ForgeAuthorizationFixture(true))){
            UUID id=adapter.submit(request(UUID.randomUUID()));initialize(process);
            respond(process,read(process),"{\"thread\":{\"id\":\"thread\"}}");
            read(process); // Tool request arrives before the turn/start acknowledgment.
            process.writeStdout("{\"id\":\"tool\",\"method\":\"item/tool/requestUserInput\",\"params\":{\"threadId\":\"thread\",\"turnId\":\"turn\"}}");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(adapter.get(id).status().equals("running") && System.nanoTime()<deadline)Thread.sleep(5);
            assertThat(adapter.get(id).status()).isEqualTo("failed");assertThat(process.isAlive()).isFalse();
        }
    }
    @Test void completed_jobs_expire_after_five_minutes()throws Exception {
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        var process=new FakeCodexProcess();try(var adapter=adapter(process,new ForgeAuthorizationFixture(true),clock)){
            UUID id=adapter.submit(request(UUID.randomUUID()));read(process);adapter.cancel(id);
            now.set(now.get().plusSeconds(299));assertThat(adapter.get(id).status()).isEqualTo("cancelled");
            now.set(now.get().plusSeconds(2));assertThatThrownBy(()->adapter.get(id)).isInstanceOf(NoSuchElementException.class);
        }
    }
    @Test void failed_late_managed_cleanup_remains_retryable_even_after_job_failure()throws Exception {
        var pipe=new FakeCodexProcess();pipe.terminateNow();
        var failing=new java.util.concurrent.atomic.AtomicBoolean(true);var stopped=new java.util.concurrent.atomic.AtomicBoolean();
        var managed=new com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess(pipe,()->{
            if(failing.get())throw new IllegalStateException("synthetic unconfirmed stop");stopped.set(true);
        });
        var auth=new ForgeAuthorizationFixture(true);var properties=new CodexAppServerProperties();properties.setRuntimeCwd(root.toString());
        CodexAppServerProcessStarter starter=cwd->{auth.service.logout();return new StartedCodexAppServer(managed,List.of("fixture"),Instant.now());};
        try(var adapter=new ForgeCodexOperationsAdapter(mapper,starter,properties,new CodexRuntimeWorkspace(properties),auth.gate,Clock.systemUTC())){
            UUID id=adapter.submit(request(UUID.randomUUID()));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(adapter.get(id).status().equals("running") && System.nanoTime()<deadline)Thread.sleep(5);
            assertThat(adapter.get(id).errorCode()).isEqualTo("CODEX_AUTH_CLEANUP_FAILED");
            assertThat(stopped).isFalse();assertThat(pipe.pendingClientRequestBytes()).isZero();
            failing.set(false);adapter.cancel(id);assertThat(stopped).isTrue();auth.service.logout();
        }finally{failing.set(false);auth.service.logout();}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void unresolved_cleanup_survives_expiry_for_delete_and_shutdown(boolean shutdown)throws Exception {
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        var pipe=new FakeCodexProcess();pipe.terminateNow();
        var failing=new java.util.concurrent.atomic.AtomicBoolean(true);var stopped=new java.util.concurrent.atomic.AtomicBoolean();
        var managed=new com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess(pipe,()->{
            if(failing.get())throw new IllegalStateException("synthetic unconfirmed stop");stopped.set(true);
        });
        var auth=new ForgeAuthorizationFixture(true);var properties=new CodexAppServerProperties();properties.setRuntimeCwd(root.toString());
        CodexAppServerProcessStarter starter=cwd->{auth.service.logout();return new StartedCodexAppServer(managed,List.of("fixture"),Instant.now());};
        try(var adapter=new ForgeCodexOperationsAdapter(mapper,starter,properties,new CodexRuntimeWorkspace(properties),auth.gate,clock)){
            UUID id=adapter.submit(request(UUID.randomUUID()));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(adapter.get(id).status().equals("running") && System.nanoTime()<deadline)Thread.sleep(5);
            assertThat(adapter.get(id).errorCode()).isEqualTo("CODEX_AUTH_CLEANUP_FAILED");
            now.set(now.get().plusSeconds(301));
            // Terminal display state must not expire the unacknowledged process obligation.
            assertThatCode(()->adapter.get(id)).doesNotThrowAnyException();
            assertThat(adapter.get(id).errorCode()).isEqualTo("CODEX_AUTH_CLEANUP_FAILED");
            assertThatThrownBy(()->adapter.submit(request(UUID.randomUUID()))).hasMessage("CODEX_LOGOUT_REQUIRED");
            failing.set(false);
            if(shutdown)adapter.close();else adapter.cancel(id);
            assertThat(stopped).isTrue();assertThat(pipe.pendingClientRequestBytes()).isZero();
            assertThat(adapter.get(id).status()).isEqualTo("cancelled");
            now.set(now.get().plusSeconds(301));
            assertThatThrownBy(()->adapter.get(id)).isInstanceOf(NoSuchElementException.class);
            auth.service.logout();
        }finally{failing.set(false);auth.service.logout();}
    }
    @Test void retained_jobs_reject_admission_when_capacity_is_full()throws Exception {
        var startAllowed=new CountDownLatch(1);var properties=new CodexAppServerProperties();properties.setRuntimeCwd(root.toString());
        var auth=new ForgeAuthorizationFixture(true);
        CodexAppServerProcessStarter starter=cwd->{
            try{if(!startAllowed.await(2,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            return new StartedCodexAppServer(new FakeCodexProcess(),List.of("fixture"),Instant.now());
        };
        try(var adapter=new ForgeCodexOperationsAdapter(mapper,starter,properties,new CodexRuntimeWorkspace(properties),auth.gate,Clock.systemUTC())){
            try{
                for(int index=0;index<64;index++)adapter.submit(request(UUID.randomUUID()));
                assertThatThrownBy(()->adapter.submit(request(UUID.randomUUID()))).isInstanceOf(IllegalStateException.class).hasMessage("CODEX_JOB_CONFLICT");
            }finally{startAllowed.countDown();}
        }
    }
}
