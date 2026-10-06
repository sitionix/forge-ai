package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.llm.ForgeCodexAuthorizationGate;
import com.sitionix.forgeagent.application.llm.ForgeCodexAuthorizationGate.AuthorizationLease;
import com.sitionix.forgeagent.domain.exception.LlmAuthorizationException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.ForgeCodexOperationsPort;
import com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Installation-local, bounded jobs. No project selection, MCP grants, or arbitrary RPC. */
@Component
final class ForgeCodexOperationsAdapter implements ForgeCodexOperationsPort,AutoCloseable,org.springframework.beans.factory.DisposableBean {
    private static final int MAX_JOBS=64;
    private static final List<String> DISABLED=List.of("shell_tool","unified_exec","unified_exec_tty","code_mode","code_mode_host",
            "browser_use","browser_use_external","browser_use_full_cdp_access","computer_use","apps","plugins","remote_plugin",
            "multi_agent","multi_agent_v2","image_generation","view_image","sleep_tool","goals","hooks","skill_search",
            "skill_mcp_dependency_install","workspace_dependencies","memories","tool_suggest","default_mode_request_user_input");
    private final ObjectMapper mapper;
    private final CodexAppServerProcessStarter starter;
    private final CodexAppServerProperties properties;
    private final CodexRuntimeWorkspace workspace;
    private final ForgeCodexAuthorizationGate gate;
    private final Clock clock;
    private final Map<UUID,Job> jobs=new HashMap<>();
    private final Map<UUID,Instant> cancelledBeforeSubmit=new HashMap<>();
    private final ExecutorService workers=Executors.newFixedThreadPool(4,Thread.ofPlatform().daemon().name("forge-codex-bridge-",0).factory());
    private final ScheduledExecutorService deadlines=Executors.newScheduledThreadPool(1,Thread.ofPlatform().daemon().name("forge-codex-deadline").factory());
    private final Semaphore observations=new Semaphore(4);
    private boolean closed;
    ForgeCodexOperationsAdapter(ObjectMapper mapper,CodexAppServerProcessStarter starter,CodexAppServerProperties properties,
            CodexRuntimeWorkspace workspace,ForgeCodexAuthorizationGate gate,Clock clock) {
        this.mapper=mapper;this.starter=starter;this.properties=properties;this.workspace=workspace;this.gate=gate;this.clock=clock;
        deadlines.scheduleWithFixedDelay(this::expire,1,1,TimeUnit.SECONDS);
    }
    @Override public Snapshot models(String cursor,Integer limit,Boolean hidden) {
        var params=mapper.createObjectNode();if(cursor!=null)params.put("cursor",cursor);if(limit!=null)params.put("limit",limit);if(hidden!=null)params.put("includeHidden",hidden);
        return observe("model/list",params,false);
    }
    @Override public Snapshot usage(){return observe("account/rateLimits/read",mapper.createObjectNode(),true);}
    private Snapshot observe(String method,JsonNode params,boolean authorized) {
        if(!observations.tryAcquire())throw new IllegalStateException("CODEX_BUSY");
        AuthorizationLease lease=null;
        try {
            synchronized(jobs){if(closed)throw new IllegalStateException("CODEX_CLOSED");}
            lease=authorized?gate.requireAuthorized():gate.observe();
            var transport=open(lease,new Capture());String version=initialize(transport);
            gate.check(lease);var result=transport.request(method,params,properties.getRequestTimeout());
            return new Snapshot(version,map(result));
        }finally{try{if(lease!=null)gate.release(lease);}finally{observations.release();}}
    }
    @Override public UUID submit(ForgeCodexGenerationRequest request) {
        synchronized(jobs){
            expire();if(closed || jobs.size()>=MAX_JOBS || jobs.containsKey(request.requestId()) || cancelledBeforeSubmit.containsKey(request.requestId()))throw new IllegalStateException("CODEX_JOB_CONFLICT");
            var lease=gate.requireAuthorized();var job=new Job(request,lease);
            try {
                gate.registerCancellation(lease,()->job.capture.fail("CODEX_JOB_CANCELLED"));
                jobs.put(request.requestId(),job);
                workers.execute(()->run(job));
                job.timeout=deadlines.schedule(()->cancel(request.requestId()),Math.max(1,(long)(request.timeoutSeconds()*1000)),TimeUnit.MILLISECONDS);
                return request.requestId();
            }catch(RuntimeException failure){jobs.remove(request.requestId());gate.release(lease);throw failure;}
        }
    }
    @Override public ForgeCodexGenerationResult get(UUID id){synchronized(jobs){expire();var job=jobs.get(id);if(job==null)throw new NoSuchElementException();return job.result;}}
    @Override public void cancel(UUID id) {
        Job job;synchronized(jobs){
            expire();job=jobs.get(id);
            if(job==null){
                if(!cancelledBeforeSubmit.containsKey(id) && cancelledBeforeSubmit.size()>=MAX_JOBS)throw new IllegalStateException("CODEX_BUSY");
                cancelledBeforeSubmit.putIfAbsent(id,clock.instant().plusSeconds(300));return;
            }
        }
        synchronized(job){if(job.completedAt!=null && job.cleanupComplete)return;job.cancelled=true;job.capture.fail("CODEX_JOB_CANCELLED");}
        gate.release(job.lease);
        synchronized(job){job.cleanupComplete=true;job.result=ForgeCodexGenerationResult.state("cancelled","CODEX_JOB_CANCELLED");job.completedAt=clock.instant();}
    }
    private void run(Job job) {
        ForgeCodexGenerationResult result;
        try {
            gate.check(job.lease);var transport=open(job.lease,job.capture);String version=initialize(transport);
            var params=threadParams(job.request);
            gate.check(job.lease);
            var thread=transport.request("thread/start",params,requestBudget(job));
            job.capture.threadId=required(thread.path("thread"),"id");
            var turn=mapper.createObjectNode();turn.put("threadId",job.capture.threadId);
            turn.putArray("input").addObject().put("type","text").put("text",job.request.prompt());
            turn.put("model",job.request.modelId());if(job.request.effortId()!=null)turn.put("effort",job.request.effortId());
            var mode=turn.putObject("collaborationMode");mode.put("mode","default");var settings=mode.putObject("settings");
            settings.put("model",job.request.modelId());if(job.request.effortId()!=null)settings.put("reasoning_effort",job.request.effortId());
            if("json_object".equals(job.request.responseMode()))turn.set("outputSchema",schema());
            var accepted=transport.request("turn/start",turn,requestBudget(job),write->gate.dispatch(job.lease,write));
            String turnId=required(accepted.path("turn"),"id");
            var terminal=job.capture.terminal.get(remaining(job).toMillis(),TimeUnit.MILLISECONDS);
            gate.check(job.lease);
            if(!turnId.equals(required(terminal.path("turn"),"id")) || !"completed".equals(required(terminal.path("turn"),"status")))throw new IllegalStateException();
            result=job.capture.result(version,turnId,terminal);
        }catch(Exception failure){
            var auth=LlmAuthorizationException.find(failure);result=ForgeCodexGenerationResult.state("failed",auth==null?"CODEX_GENERATION_FAILED":auth.code());
        }
        boolean cleaned=false;
        try{gate.release(job.lease);cleaned=true;}catch(RuntimeException failure){result=ForgeCodexGenerationResult.state("failed","CODEX_AUTH_CLEANUP_FAILED");}
        synchronized(job){
            if(job.completedAt!=null && job.cleanupComplete)return;
            job.cleanupComplete=cleaned;
            if(job.cancelled && cleaned)result=ForgeCodexGenerationResult.state("cancelled","CODEX_JOB_CANCELLED");
            job.result=result;job.completedAt=clock.instant();if(job.timeout!=null)job.timeout.cancel(false);
        }
    }
    private Duration remaining(Job job) {
        long millis=Duration.between(clock.instant(),job.deadline).toMillis();if(millis<=0)throw new IllegalStateException("CODEX_DEADLINE");
        return Duration.ofMillis(Math.min(millis,properties.getTurnTimeout().toMillis()));
    }
    private Duration requestBudget(Job job){var remaining=remaining(job);return remaining.compareTo(properties.getRequestTimeout())<0?remaining:properties.getRequestTimeout();}
    private ObjectNode threadParams(ForgeCodexGenerationRequest request) {
        var params=mapper.createObjectNode();params.put("cwd",workspace.routingWorkspace().cwd().toString());
        params.put("model",request.modelId());params.put("modelProvider","openai");params.put("approvalPolicy","never");params.put("sandbox","read-only");
        params.put("ephemeral",true);params.putArray("environments");params.putArray("dynamicTools");
        var config=params.putObject("config");config.put("model_provider","openai");config.put("cli_auth_credentials_store","file");config.put("forced_login_method","chatgpt");config.put("web_search","disabled");
        var features=config.putObject("features");DISABLED.forEach(name->features.put(name,false));return params;
    }
    private ObjectNode schema(){
        var schema=mapper.createObjectNode();schema.put("type","object");schema.putObject("properties").putObject("json").put("type","string")
                .put("description","The complete JSON object requested by the user, serialized as a JSON string. The string itself must parse as a JSON object.");
        schema.putArray("required").add("json");schema.put("additionalProperties",false);return schema;
    }
    private CodexJsonRpcTransport open(AuthorizationLease lease,Capture capture) {
        gate.check(lease);
        // Plain startup deliberately supplies no MCP launch grants.
        var started=starter.start(workspace.routingWorkspace().cwd());var owned=new AtomicReference<CodexJsonRpcTransport>();
        Runnable cleanup=()->{
            var transport=owned.get();if(transport!=null){transport.close();return;}
            var process=started.process();if(process instanceof ManagedRuntimeProcess managed)managed.terminateOwnedUnit();
            if(!process.isAlive())return;CodexProcessTree.capture(process).terminateTree();
            try{if(!process.waitFor(properties.getForceKillTimeout().toMillis(),TimeUnit.MILLISECONDS))throw new IllegalStateException("CODEX_CLEANUP_FAILED");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("CODEX_CLEANUP_FAILED");}
        };
        gate.registerCancellation(lease,cleanup);
        capture.abort=cleanup;
        var transport=new CodexJsonRpcTransport(mapper,started,properties,(method,params)->{
            capture.fail("CODEX_TOOLS_FORBIDDEN");cleanup.run();throw new UnsupportedOperationException();
        },capture);
        owned.set(transport);gate.registerCancellation(lease,transport::close);return transport;
    }
    private String initialize(CodexJsonRpcTransport transport) {
        var params=mapper.createObjectNode();params.putObject("clientInfo").put("name","forge_knowledge_bridge").put("version","1");params.putObject("capabilities").put("experimentalApi",true);
        var result=transport.request("initialize",params,properties.getRequestTimeout());
        String agent=required(result,"userAgent");String version=agent.replaceFirst("^[^/]+/([^\\s]+).*$","$1");
        // Both patch versions use the characterized authentication/restriction contract.
        if(!Set.of("0.160.0","0.160.1").contains(version))throw new IllegalStateException("CODEX_VERSION_UNSUPPORTED");
        transport.notify("initialized",mapper.createObjectNode());return version;
    }
    private static String required(JsonNode node,String key){var value=node.get(key);if(value==null || !value.isTextual() || value.textValue().isBlank())throw new IllegalStateException();return value.textValue();}
    private Map<String,Object> map(JsonNode node){return mapper.convertValue(node,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
    private void expire(){synchronized(jobs){
        // Unacknowledged cleanup keeps its slot in MAX_JOBS until DELETE/shutdown confirms it.
        jobs.values().removeIf(job->job.cleanupComplete && job.completedAt!=null && !job.completedAt.plusSeconds(300).isAfter(clock.instant()));
        cancelledBeforeSubmit.values().removeIf(expires->!expires.isAfter(clock.instant()));
    }}
    @Override public void destroy(){close();}
    @Override public void close(){
        List<UUID> ids;synchronized(jobs){closed=true;ids=List.copyOf(jobs.keySet());}
        deadlines.shutdownNow();RuntimeException failure=null;
        for(UUID id:ids)try{cancel(id);}catch(RuntimeException e){failure=e;}
        workers.shutdownNow();if(failure!=null)throw failure;
    }
    private final class Job {
        final ForgeCodexGenerationRequest request;final AuthorizationLease lease;final Capture capture=new Capture();final Instant deadline;
        volatile ForgeCodexGenerationResult result=ForgeCodexGenerationResult.state("running",null);
        volatile Instant completedAt;volatile boolean cancelled;volatile boolean cleanupComplete;volatile ScheduledFuture<?> timeout;
        Job(ForgeCodexGenerationRequest request,AuthorizationLease lease){this.request=request;this.lease=lease;deadline=clock.instant().plusMillis((long)(request.timeoutSeconds()*1000));}
    }
    private final class Capture implements CodexTransportEventHandler {
        final CompletableFuture<JsonNode> terminal=new CompletableFuture<>();volatile String threadId;
        volatile Runnable abort=()->{};
        String text="";JsonNode tokenUsage;int bytes;int notifications;
        @Override public synchronized void handleNotification(String method,JsonNode params) {
            if(++notifications>4096 || (bytes+=params.toString().length())>4*1024*1024){fail("CODEX_OUTPUT_LIMIT");abort.run();return;}
            if(threadId==null || !threadId.equals(params.path("threadId").asText()))return;
            if(method.equals("item/started") || method.equals("item/completed")) {
                var item=params.path("item");String type=item.path("type").asText();
                if(!Set.of("agentMessage","reasoning","userMessage").contains(type)){fail("CODEX_TOOLS_FORBIDDEN");abort.run();return;}
                if(method.equals("item/completed") && type.equals("agentMessage") && !"commentary".equals(item.path("phase").asText()))text=item.path("text").asText();
            }else if(method.equals("thread/tokenUsage/updated"))tokenUsage=params.path("tokenUsage");
            else if(method.equals("turn/completed"))terminal.complete(params.deepCopy());
        }
        @Override public void transportFailed(RuntimeException failure){terminal.completeExceptionally(new IllegalStateException("CODEX_TRANSPORT_FAILED"));}
        void fail(String code){terminal.completeExceptionally(new IllegalStateException(code));}
        synchronized ForgeCodexGenerationResult result(String version,String turnId,JsonNode notification){
            var terminalTurn=notification.path("turn");var usage=notification.has("tokenUsage")?notification.get("tokenUsage"):terminalTurn.has("tokenUsage")?terminalTurn.get("tokenUsage"):tokenUsage;
            var warnings=notification.has("warnings")?notification.get("warnings"):terminalTurn.path("warnings");
            Map<String,Object> metadata=new HashMap<>();
            for(String key:List.of("model","modelId","requestedModel","resolvedModel","serviceTier")){
                var value=notification.has(key)?notification.get(key):terminalTurn.path(key);if(value.isValueNode() && !value.isNull())metadata.put(key,mapper.convertValue(value,Object.class));
            }
            if(text.isBlank())throw new IllegalStateException("CODEX_EMPTY_RESPONSE");
            return new ForgeCodexGenerationResult("completed",text,threadId,turnId,version,usage!=null && usage.isObject()?map(usage):null,
                    warnings.isArray()?mapper.convertValue(warnings,new com.fasterxml.jackson.core.type.TypeReference<List<Object>>(){}):List.of(),metadata,null);
        }
    }
}
