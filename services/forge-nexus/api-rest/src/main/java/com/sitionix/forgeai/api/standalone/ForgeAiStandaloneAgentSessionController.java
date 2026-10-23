package com.sitionix.forgeai.api.standalone;
import java.util.UUID;
import com.sitionix.forgeai.domain.usecase.StandaloneAgentSessionUseCases;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sitionix.forgeai.api.standalone.StandaloneSessionDtos.*;
import static com.sitionix.forgeai.api.standalone.StandaloneSessionApiMapper.*;
@RestController
@RequestMapping("/api/v1/infrastructure/agents/projects/{projectId}/agent-sessions")
public class ForgeAiStandaloneAgentSessionController {
 private final StandaloneAgentSessionUseCases usecases;
 public ForgeAiStandaloneAgentSessionController(StandaloneAgentSessionUseCases usecases){this.usecases=usecases;}
 @PostMapping("/launch-preview") public Preview preview(@PathVariable UUID projectId,@RequestBody PreviewRequest r){return map(usecases.preview(projectId,request(r)));}
 @PostMapping public ResponseEntity<Receipt> create(@PathVariable UUID projectId,@RequestBody Create r){return receipt(map(usecases.create(projectId,request(r))));}
 @GetMapping public SessionPage list(@PathVariable UUID projectId,@RequestParam(defaultValue="")String search,@RequestParam(required=false)String before,@RequestParam(defaultValue="50")int limit){return map(usecases.list(projectId,search,before,limit));}
 @GetMapping("/{sessionId}")public Details get(@PathVariable UUID projectId,@PathVariable UUID sessionId){return map(usecases.get(projectId,sessionId));}
 @GetMapping("/{sessionId}/messages")public MessagePage messages(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestParam(defaultValue="0")long afterSequence,@RequestParam(defaultValue="100")int limit){return map(usecases.messages(projectId,sessionId,afterSequence,limit));}
 @GetMapping("/{sessionId}/events")public EventPage events(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestParam(defaultValue="0")long afterSequence,@RequestParam(defaultValue="100")int limit){return map(usecases.events(projectId,sessionId,afterSequence,limit));}
 @PostMapping("/{sessionId}/messages")public ResponseEntity<Receipt> send(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestBody Send r){return receipt(map(usecases.send(projectId,sessionId,request(r))));}
 @PostMapping("/{sessionId}/cancel")public ResponseEntity<Receipt> cancel(@PathVariable UUID projectId,@PathVariable UUID sessionId,@RequestBody Cancel r){return receipt(map(usecases.cancel(projectId,sessionId,request(r))));}
 private ResponseEntity<Receipt> receipt(Receipt r){return ResponseEntity.status(r.accepted()?202:200).cacheControl(CacheControl.noStore()).body(r);}
}
