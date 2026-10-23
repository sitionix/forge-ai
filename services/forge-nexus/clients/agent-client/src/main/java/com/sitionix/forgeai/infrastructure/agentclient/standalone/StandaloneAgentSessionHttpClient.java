package com.sitionix.forgeai.infrastructure.agentclient.standalone;
import java.util.UUID;
import org.springframework.web.service.annotation.*;
import org.springframework.web.bind.annotation.*;
import static com.sitionix.forgeai.infrastructure.agentclient.standalone.StandaloneSessionClientDtos.*;
@HttpExchange("/api/v1/projects/{projectId}/agent-sessions")
public interface StandaloneAgentSessionHttpClient {
 @PostExchange("/launch-preview") Preview preview(@PathVariable UUID projectId,@RequestBody PreviewInternal request);
 @PostExchange Receipt create(@PathVariable UUID projectId,@RequestBody CreateInternal request);
 @GetExchange SessionPage list(@PathVariable UUID projectId,@RequestParam String search,@RequestParam(required=false)String before,@RequestParam int limit);
 @GetExchange("/{sessionId}") Details get(@PathVariable UUID projectId,@PathVariable UUID sessionId);
 @GetExchange("/{sessionId}/messages") MessagePage messages(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestParam long afterSequence,@RequestParam int limit);
 @GetExchange("/{sessionId}/events") EventPage events(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestParam long afterSequence,@RequestParam int limit);
 @PostExchange("/{sessionId}/messages") Receipt send(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestBody Send request);
 @PostExchange("/{sessionId}/cancel") Receipt cancel(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestBody Cancel request);
}
