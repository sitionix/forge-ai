package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** Real inference probe through the existing transport, without changing production version gates. */
@EnabledIfSystemProperty(named="forge.codex.standalone-smoke.binary", matches=".+")
class CodexStandaloneRuntimeSmokeTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final Duration timeout = Duration.ofSeconds(45);

    @Test void installedRuntimeRecognizesNativeConcurrencyAndDepthLimits() throws Exception {
        Path home=Files.createDirectories(root.resolve("config-limits-home"));
        for(String field:List.of("agents.max_threads","agents.max_depth")) {
            var builder=new ProcessBuilder(System.getProperty("forge.codex.standalone-smoke.binary"),"features","list","-c",field+"=\"not-a-number\"");
            builder.environment().clear();builder.environment().putAll(Map.of("HOME",home.toString(),"CODEX_HOME",home.toString(),"PATH","/usr/bin:/bin"));
            var process=builder.start();assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();
            String error=new String(process.getErrorStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).isNotZero();assertThat(error).contains("invalid type","agents.");
        }
    }

    @Test void productionSharedAdapterPreservesPlainRootConversation() throws Exception {
        Path home=Files.createDirectories(root.resolve("adapter-home"));Path config=Files.createDirectories(home.resolve(".codex"));
        String auth=System.getProperty("forge.codex.standalone-smoke.auth");if(auth!=null)Files.createSymbolicLink(config.resolve("auth.json"),Path.of(auth).toAbsolutePath());
        Path project=Files.createDirectories(root.resolve("adapter-project"));var processes=new CopyOnWriteArrayList<Process>();
        CodexAppServerProcessStarter starter=new CodexAppServerProcessStarter(){public StartedCodexAppServer start(Path cwd){try{
            var builder=new ProcessBuilder(System.getProperty("forge.codex.standalone-smoke.binary"),"app-server","--stdio").directory(cwd.toFile());builder.environment().clear();builder.environment().putAll(Map.of("HOME",home.toString(),"CODEX_HOME",config.toString(),"PATH","/usr/bin:/bin","LANG","C.UTF-8"));
            var process=builder.start();processes.add(process);return new StartedCodexAppServer(process,List.of("isolated-production-adapter-smoke"),Instant.now());
        }catch(java.io.IOException e){throw new IllegalStateException(e);}}
            // This isolated fixture verifies provider protocol; launcher pinning is tested separately.
            public StartedCodexAppServer startPinned(Path cwd,com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants grants,Map<String,String> resources){return start(cwd);}
        };
        var props=new CodexAppServerProperties();props.setTurnTimeout(Duration.ofSeconds(180));
        var client=new CodexAppServerClient(json,starter,props,new CodexRuntimeWorkspace(props),null,new ForgeAuthorizationFixture(true).gate);
        var adapter=new CodexAgentTurnExecutionAdapter(client,json);var space=new com.sitionix.forgeagent.application.runtime.ExecutionWorkspace(project,List.of(project));
        var d=new com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition("FORGE","selected","Selected root","Always end your final reply with ROOT-INSTRUCTIONS-4391.",new com.sitionix.forgeagent.domain.model.AgentModelSelection("codex",System.getProperty("forge.codex.live-model","gpt-5.6-sol"),null),"snapshot",Map.of(),true);
        var thread=new java.util.concurrent.atomic.AtomicReference<String>();var version=new java.util.concurrent.atomic.AtomicReference<String>();var turns=new java.util.concurrent.atomic.AtomicInteger();
        var callbacks=new com.sitionix.forgeagent.application.runtime.AgentTurnIdentityCallbacks(){public void conversationStarted(String id,String v){assertThat(thread.get()).isNull();thread.set(id);version.set(v);}public void turnStarted(String id){turns.incrementAndGet();}};
        UUID session=UUID.randomUUID(),projectId=UUID.randomUUID();String fact="adapter-fact-"+UUID.randomUUID();
        try {
            for(int i=0;i<2;i++) {
                UUID execution=UUID.randomUUID();var owner=new com.sitionix.forgeagent.domain.model.execution.AgentExecutionOwner.Standalone(projectId,session,execution);
                var request=new com.sitionix.forgeagent.application.runtime.AgentTurnExecutionRequest(execution,d,i==0?"Remember exactly "+fact:"Return the exact fact from the previous message.",null,space,com.sitionix.forgeagent.application.runtime.AgentTurnExecutionRequest.Mode.DURABLE,thread.get(),version.get(),false,null,null,callbacks,owner);
                assertThat(adapter.execute(request,com.sitionix.forgeagent.application.runtime.AgentTurnEventSink.noop()).text()).contains(fact,"ROOT-INSTRUCTIONS-4391");
            }
            assertThat(turns.get()).isEqualTo(2);assertThat(version.get()).isEqualTo("0.163.0");assertThat(thread.get()).isNotBlank();
        }finally{client.close();}
        assertThat(processes).hasSize(2).allMatch(p->!p.isAlive());
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="forge.codex.standalone-smoke.managed-driver",matches=".+")
    void managedRootUsesPinnedSkillChildAndResumesWithOwnedCleanup() throws Exception {
        String driver=System.getProperty("forge.codex.standalone-smoke.managed-driver"),fixture=System.getProperty("forge.codex.standalone-smoke.managed-fixture");
        Path workspace=Path.of(System.getProperty("forge.codex.standalone-smoke.managed-workspace"));
        var space=new com.sitionix.forgeagent.application.runtime.ExecutionWorkspace(workspace,List.of(workspace));
        var resources=new CodexNativeAgentDefinitionAdapter("").resources(space);var processes=new CopyOnWriteArrayList<Process>();
        var executionIds=new CopyOnWriteArrayList<String>();
        CodexAppServerProcessStarter starter=new CodexAppServerProcessStarter(){
            public StartedCodexAppServer start(Path cwd){throw new AssertionError("Unpinned launch forbidden");}
            public StartedCodexAppServer startPinned(Path cwd,com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants grants,Map<String,String> snapshot){
                try {
                    assertThat(grants.isEmpty()).isTrue();String id=UUID.randomUUID().toString();executionIds.add(id);
                    var process=new ProcessBuilder("/bin/bash","-p",System.getProperty("forge.codex.standalone-smoke.sudo-helper"),"/usr/bin/python3","-I",driver,"launch",fixture,id).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                    process.getOutputStream().write(json.writeValueAsBytes(snapshot));process.getOutputStream().write('\n');process.getOutputStream().flush();
                    var owned=new com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess(process,()->{
                        try{var stop=new ProcessBuilder("/bin/bash","-p",System.getProperty("forge.codex.standalone-smoke.sudo-helper"),"/usr/bin/python3","-I",driver,"stop",fixture,id).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                            if(!stop.waitFor(55,TimeUnit.SECONDS)||stop.exitValue()!=0)throw new IllegalStateException("Managed cleanup unconfirmed");
                        }catch(Exception failure){throw new IllegalStateException(failure);}
                    });processes.add(owned);return new StartedCodexAppServer(owned,List.of("managed-standalone-smoke"),Instant.now());
                }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            }
        };
        var props=new CodexAppServerProperties();props.setTurnTimeout(Duration.ofSeconds(180));
        var client=new CodexAppServerClient(json,starter,props,new CodexRuntimeWorkspace(props),null,new ForgeAuthorizationFixture(true).gate);
        var adapter=new CodexAgentTurnExecutionAdapter(client,json);
        var d=new com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition("FORGE","managed-selected","Managed selected root","Always end with MANAGED-ROOT-4391.",new com.sitionix.forgeagent.domain.model.AgentModelSelection("codex",System.getProperty("forge.codex.live-model","gpt-5.6-sol"),"low"),"managed-fingerprint",resources,true);
        var thread=new java.util.concurrent.atomic.AtomicReference<String>();var version=new java.util.concurrent.atomic.AtomicReference<String>();var turns=new java.util.concurrent.atomic.AtomicInteger();
        var callbacks=new com.sitionix.forgeagent.application.runtime.AgentTurnIdentityCallbacks(){public void conversationStarted(String id,String v){assertThat(thread.get()).isNull();thread.set(id);version.set(v);}public void turnStarted(String id){turns.incrementAndGet();}};
        var activity=new CopyOnWriteArrayList<com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate>();
        var sink=new com.sitionix.forgeagent.application.runtime.AgentTurnEventSink(){public void executionEvent(com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate e){activity.add(e);}};
        UUID projectId=UUID.randomUUID(),session=UUID.randomUUID();
        try {
            for(int i=0;i<2;i++){
                UUID id=UUID.randomUUID();String input=i==0?"Use the forge-proof native Skill. Invoke native spawn_agent with agent_type forge-proof-child to ask its marker, wait for it, and return both proof markers. Actually invoke the Skill and native child, do not simulate.":"Return the Skill and child proof markers from our previous conversation.";
                var request=new com.sitionix.forgeagent.application.runtime.AgentTurnExecutionRequest(id,d,input,null,space,com.sitionix.forgeagent.application.runtime.AgentTurnExecutionRequest.Mode.DURABLE,thread.get(),version.get(),false,null,null,callbacks,new com.sitionix.forgeagent.domain.model.execution.AgentExecutionOwner.Standalone(projectId,session,id));
                assertThat(adapter.execute(request,sink).text()).contains("MANAGED-SKILL-7341","MANAGED-CHILD-8290","MANAGED-ROOT-4391");
            }
            assertThat(turns.get()).isEqualTo(2);assertThat(version.get()).isEqualTo("0.163.0");
            assertThat(activity).anyMatch(e->"subagent".equals(e.phase()));
            assertThat(activity).anyMatch(e->e.type()==com.sitionix.forgeagent.domain.model.AgentExecutionEventType.COMMAND && e.payload().contains("scripts/proof.sh") && e.payload().contains("MANAGED-SKILL-7341"));
        }finally{client.close();}
        assertThat(processes).hasSize(2).allMatch(p->!p.isAlive());
    }

    @Test void rootUsesNativeSkillAndDelegatesToNativeChild() throws Exception {
        Path home=Files.createDirectories(root.resolve("delegation-home"));Path config=Files.createDirectories(home.resolve(".codex"));
        String auth=System.getProperty("forge.codex.standalone-smoke.auth");if(auth!=null)Files.createSymbolicLink(config.resolve("auth.json"),Path.of(auth).toAbsolutePath());
        Path workspace=Files.createDirectories(root.resolve("delegation-project"));
        Path skill=Files.createDirectories(workspace.resolve(".agents/skills/forge-proof"));
        Files.writeString(skill.resolve("SKILL.md"),"---\nname: forge-proof\ndescription: Use when asked for the Forge proof marker\n---\nThe Forge proof marker is SKILL-PROOF-7341. Include it in your answer.\n");
        Path localAgents=Files.createDirectories(workspace.resolve(".codex/agents"));Path globalAgents=Files.createDirectories(config.resolve("agents"));
        Files.writeString(globalAgents.resolve("forge-proof-child.toml"),"name='forge-proof-child'\ndescription='A child proof agent'\ndeveloper_instructions='Reply only GLOBAL-CHILD-IS-NOT-SELECTED.'\n");
        Files.writeString(localAgents.resolve("forge-proof-child.toml"),"name='forge-proof-child'\ndescription='A child proof agent'\ndeveloper_instructions='Reply only CHILD-PROOF-8290.'\n");
        try(Probe p=new Probe(home,workspace)) {
            ObjectNode params=params(workspace);var c=(ObjectNode)params.path("config");c.putObject("features").put("multi_agent",true);c.putObject("agents").put("max_threads",2).put("max_depth",1);
            String thread=new CodexSessionProtocol(json).startDurableThread(p.transport,params,timeout);
            String response=p.turn(thread,"Use the forge-proof native Skill. Then use your native spawn_agent tool with agent_type forge-proof-child to delegate a harmless task to that custom child: ask it for its proof marker. Wait for that child and reply with the Skill marker and child response. You must actually invoke the native child tool; do not simulate delegation.");
            assertThat(response).contains("SKILL-PROOF-7341","CHILD-PROOF-8290");
            assertThat(p.items).anyMatch(item->item.path("type").asText().toLowerCase().contains("collab"));
            assertThat(p.rootItems).anyMatch(item->item.path("type").asText().equals("subAgentActivity")&&item.path("kind").asText().equals("started")&&!item.path("agentThreadId").asText().isBlank()&&!item.path("agentThreadId").asText().equals(thread)
                    ||item.path("tool").asText().equals("spawnAgent")&&item.path("senderThreadId").asText().equals(thread)&&!item.path("receiverThreadIds").isEmpty());
        }
    }

    @Test void interruptedTurnCanBeInspectedWithoutReplay() throws Exception {
        Path home=Files.createDirectories(root.resolve("interrupt-home"));Path config=Files.createDirectories(home.resolve(".codex"));
        String auth=System.getProperty("forge.codex.standalone-smoke.auth");if(auth!=null)Files.createSymbolicLink(config.resolve("auth.json"),Path.of(auth).toAbsolutePath());
        Path workspace=Files.createDirectories(root.resolve("interrupt-project"));
        try(Probe p=new Probe(home,workspace)) {
            String thread=new CodexSessionProtocol(json).startDurableThread(p.transport,params(workspace),timeout);p.rootThread=thread;
            var input=json.createObjectNode().put("threadId",thread);input.putArray("input").addObject().put("type","text").put("text","Explain how to derive the first 100 prime numbers carefully.").putArray("text_elements");
            String turn=new CodexSessionProtocol(json).startTurn(p.transport,input,timeout);
            assertThat(p.activity.await(30,TimeUnit.SECONDS)).isTrue();
            p.transport.request("turn/interrupt",json.createObjectNode().put("threadId",thread).put("turnId",turn),timeout);
            var completed=p.completed.poll(45,TimeUnit.SECONDS);assertThat(completed).isNotNull();
            var stored=p.transport.request("thread/turns/list",json.createObjectNode().put("threadId",thread).put("itemsView","full"),timeout);
            assertThat(stored.path("data").get(0).path("id").asText()).isEqualTo(turn);
            assertThat(stored.path("data").get(0).path("status").asText()).isEqualTo("interrupted");
        }
    }

    @Test void durableRootResumesAcrossOwnedProcesses() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path config = Files.createDirectories(home.resolve(".codex"));
        Path workspace = Files.createDirectories(root.resolve("project"));
        String auth = System.getProperty("forge.codex.standalone-smoke.auth");
        if (auth != null) Files.createSymbolicLink(config.resolve("auth.json"), Path.of(auth).toAbsolutePath());
        String fact = "forge-" + UUID.randomUUID();
        String thread;
        try (Probe first = new Probe(home,workspace)) {
            ObjectNode params = params(workspace);
            thread = new CodexSessionProtocol(json).startDurableThread(first.transport,params,timeout);
            assertThat(first.turn(thread,"Remember exactly " + fact + ". Reply with that value only.")).contains(fact);
            assertThat(first.transport.request("thread/turns/list",json.createObjectNode().put("threadId",thread),timeout).path("data").size()).isEqualTo(1);
        }
        try (Probe next = new Probe(home,workspace)) {
            assertThat(new CodexSessionProtocol(json).resumeThread(next.transport,thread,"Respond plainly.",params(workspace),timeout)).isEqualTo(thread);
            assertThat(next.turn(thread,"What exact value did I ask you to remember? Reply with it only.")).contains(fact);
            assertThat(next.transport.request("thread/turns/list",json.createObjectNode().put("threadId",thread),timeout).path("data").size()).isEqualTo(2);
        }
    }

    private ObjectNode params(Path workspace) {
        ObjectNode p=json.createObjectNode().put("cwd",workspace.toString()).put("model",System.getProperty("forge.codex.live-model","gpt-5.6-sol"))
                .put("developerInstructions","Respond plainly.").put("sandbox","workspace-write").put("approvalPolicy","never");
        p.putArray("runtimeWorkspaceRoots").add(workspace.toString());p.putObject("config").put("web_search","disabled");
        return p;
    }
    private final class Probe implements AutoCloseable {
        final Process process;
        final CodexJsonRpcTransport transport;
        final BlockingQueue<JsonNode> completed = new LinkedBlockingQueue<>();
        final StringBuilder text = new StringBuilder();
        final List<JsonNode> items = new CopyOnWriteArrayList<>();
        final List<JsonNode> rootItems = new CopyOnWriteArrayList<>();
        final List<String> trace = new CopyOnWriteArrayList<>();
        volatile String rootThread;
        final CountDownLatch activity = new CountDownLatch(1);
        Probe(Path home,Path workspace) throws Exception {
            ProcessBuilder b=new ProcessBuilder(System.getProperty("forge.codex.standalone-smoke.binary"),"app-server","--stdio").directory(workspace.toFile());
            b.environment().clear();b.environment().putAll(Map.of("HOME",home.toString(),"CODEX_HOME",home.resolve(".codex").toString(),"PATH","/usr/bin:/bin","LANG","C.UTF-8"));
            process=b.start();
            transport=new CodexJsonRpcTransport(json,new StartedCodexAppServer(process,List.of("isolated-standalone-smoke"),Instant.now()),new CodexAppServerProperties(),CodexServerRequestHandler.unsupported(),new CodexTransportEventHandler(){
                public void transportFailed(RuntimeException e) { completed.offer(json.createObjectNode().put("transportFailure",e.getClass().getSimpleName())); }
                public void handleNotification(String method,JsonNode p) {
                    if(method.equals("item/started")&&Objects.equals(rootThread,p.path("threadId").asText()))activity.countDown();
                    if(!method.toLowerCase().contains("delta"))trace.add(method+":"+p.path("item").path("type").asText()+":"+p.path("item").path("status").asText());
                    if(method.equals("item/completed")) items.add(p.path("item"));
                    if(method.equals("item/completed")&&Objects.equals(rootThread,p.path("threadId").asText()))rootItems.add(p.path("item"));
                    if(method.equals("item/completed") && Objects.equals(rootThread,p.path("threadId").asText()) && p.path("item").path("type").asText().equals("agentMessage")) text.append(p.path("item").path("text").asText());
                    if(method.equals("turn/completed") && Objects.equals(rootThread,p.path("threadId").asText())) completed.offer(p);
                }
            });
            ObjectNode init=json.createObjectNode();init.putObject("clientInfo").put("name","forge-standalone-probe").put("version","1");init.putObject("capabilities").put("experimentalApi",true);
            assertThat(transport.request("initialize",init,timeout).toString()).contains("0.163.0");
            transport.notify("initialized",json.createObjectNode());
        }
        String turn(String thread,String input) throws Exception {
            rootThread=thread;text.setLength(0);ObjectNode p=json.createObjectNode().put("threadId",thread);p.putArray("input").addObject().put("type","text").put("text",input).putArray("text_elements");
            new CodexSessionProtocol(json).startTurn(transport,p,timeout);
            JsonNode done=completed.poll(90,TimeUnit.SECONDS);assertThat(done).describedAs("Native protocol trace: %s",trace).isNotNull();assertThat(done.path("turn").path("status").asText()).describedAs("Provider outcome: %s",done.path("turn").path("error").path("codexErrorInfo")).isEqualTo("completed");return text.toString();
        }
        public void close() { transport.close();assertThat(process.isAlive()).isFalse(); }
    }
}
