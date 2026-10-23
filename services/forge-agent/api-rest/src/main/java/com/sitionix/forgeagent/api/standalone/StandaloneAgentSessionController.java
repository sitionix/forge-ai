package com.sitionix.forgeagent.api.standalone;
import com.sitionix.forgeagent.application.standalone.*;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.standalone.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sitionix.forgeagent.api.standalone.StandaloneSessionDtos.*;
import static com.sitionix.forgeagent.api.standalone.StandaloneSessionApiMapper.map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-sessions")
public class StandaloneAgentSessionController {
 private final StandaloneSessionCommands commands;private final StandaloneSessionQueries queries;
 public StandaloneAgentSessionController(StandaloneSessionCommands commands,StandaloneSessionQueries queries){this.commands=commands;this.queries=queries;}
 @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
 public ResponseEntity<com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse> denied(org.springframework.web.server.ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).body(new com.sitionix.forgeagent.api.dto.ForgeAgentErrorResponse("SESSION_SERVICE_DENIED","Local service authentication required.",null));}
 @ModelAttribute public void authenticate(HttpServletRequest request){if(!Boolean.TRUE.equals(request.getAttribute("forgeStandaloneServiceAuthenticated")))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);}
 @PostMapping("/launch-preview") public Preview preview(@PathVariable UUID projectId,@RequestBody PreviewRequest request) {
  try {var d=commands.preview(projectId,request.agentSource(),request.agentId(),request.repositoryIds(),request.fallback());return new Preview(d.source(),d.reference(),d.name(),d.model().providerId(),d.model().modelId(),d.model().effortId(),d.fingerprint(),true,null,StandaloneSessionPolicy.MAX_MESSAGE_CHARACTERS);}
  catch(ConflictException unavailable){return new Preview(request.agentSource(),request.agentId(),null,null,null,null,null,false,unavailable.code(),StandaloneSessionPolicy.MAX_MESSAGE_CHARACTERS);}
 }
 @PostMapping public ResponseEntity<StandaloneCommandReceipt> create(@PathVariable UUID projectId,@RequestBody CreateInternal request){return receipt(commands.create(projectId,request.request(),request.fallback()));}
 @GetMapping public SessionPage list(@PathVariable UUID projectId,@RequestParam(defaultValue="")String search,@RequestParam(required=false)String before,@RequestParam(defaultValue="50")int limit){
  var rows=queries.list(projectId,search,before,limit);String next=rows.isEmpty()?null:rows.getLast().lastActivityAt()+"|"+rows.getLast().id();return new SessionPage(rows.stream().map(StandaloneSessionApiMapper::map).toList(),next,rows.size()==limit);
 }
 @GetMapping("/{sessionId}") public Details get(@PathVariable UUID projectId,@PathVariable UUID sessionId){return new Details(map(queries.get(projectId,sessionId)),messages(projectId,sessionId,0,100));}
 @GetMapping("/{sessionId}/messages") public MessagePage messages(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestParam(defaultValue="0")long afterSequence,@RequestParam(defaultValue="100")int limit){
  var rows=queries.messages(projectId,sessionId,afterSequence,limit);return new MessagePage(rows.stream().map(StandaloneSessionApiMapper::map).toList(),rows.isEmpty()?afterSequence:rows.getLast().sequence(),rows.size()==limit);
 }
 @PostMapping("/{sessionId}/messages") public ResponseEntity<StandaloneCommandReceipt> send(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestBody StandaloneSessionCommands.Send request){return receipt(commands.send(projectId,sessionId,request));}
 @GetMapping("/{sessionId}/events") public EventPage events(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestParam(defaultValue="0")long afterSequence,@RequestParam(defaultValue="100")int limit){
  var rows=queries.events(projectId,sessionId,afterSequence,limit);return new EventPage(map(queries.get(projectId,sessionId)),rows.stream().map(StandaloneSessionApiMapper::map).toList(),rows.isEmpty()?afterSequence:rows.getLast().sequence(),rows.size()==limit);
 }
 @PostMapping("/{sessionId}/cancel") public ResponseEntity<StandaloneCommandReceipt> cancel(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestBody StandaloneSessionCommands.Cancel request){return receipt(commands.cancel(projectId,sessionId,request));}
 private ResponseEntity<StandaloneCommandReceipt> receipt(StandaloneCommandReceipt result){return ResponseEntity.status(result.accepted()?202:200).cacheControl(CacheControl.noStore()).body(result);}
}
