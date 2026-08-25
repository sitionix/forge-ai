package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.*;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogTargetCandidate;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectLogs;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/infrastructure/agents/projects/{projectId}")
public class ForgeAiProjectLogsController {
  private final ManageAgentProjectLogs logs;
  private final AgentProxyApiMapper mapper;

  @GetMapping("/log-sources")
  public List<AgentLogSourceResponse> list(@PathVariable UUID projectId) {
    return logs.list(projectId).stream().map(mapper::toResponse).toList();
  }

  @PostMapping("/log-sources")
  public ResponseEntity<AgentLogSourceResponse> create(
      @PathVariable UUID projectId, @Valid @RequestBody AgentLogSourceRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(mapper.toResponse(logs.create(projectId, mapper.toCommand(request))));
  }

  @PutMapping("/log-sources/{sourceId}")
  public AgentLogSourceResponse update(
      @PathVariable UUID projectId,
      @PathVariable UUID sourceId,
      @Valid @RequestBody AgentLogSourceRequest request) {
    return mapper.toResponse(logs.update(projectId, sourceId, mapper.toCommand(request)));
  }

  @DeleteMapping("/log-sources/{sourceId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID projectId, @PathVariable UUID sourceId) {
    logs.delete(projectId, sourceId);
  }

  @PostMapping("/log-sources/discover")
  public List<AgentLogTargetCandidate> discover(
      @PathVariable UUID projectId, @Valid @RequestBody AgentLogDiscoveryRequest request) {
    return logs.discover(projectId, mapper.toCommand(request));
  }

  @PostMapping("/log-sources/validate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void validate(
      @PathVariable UUID projectId, @Valid @RequestBody AgentLogSourceRequest request) {
    logs.validate(projectId, mapper.toCommand(request));
  }

  @GetMapping("/ssh-connections")
  public List<AgentSshConnectionResponse> listSsh(@PathVariable UUID projectId) {
    return logs.listSshConnections(projectId).stream().map(mapper::toResponse).toList();
  }

  @PostMapping("/ssh-connections")
  @ResponseStatus(HttpStatus.CREATED)
  public AgentSshConnectionResponse createSsh(
      @PathVariable UUID projectId, @Valid @RequestBody AgentSshConnectionRequest request) {
    return mapper.toResponse(logs.createSshConnection(projectId, mapper.toCommand(request)));
  }

  @GetMapping(path = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<StreamingResponseBody> stream(
      @PathVariable UUID projectId,
      @RequestParam List<UUID> sourceId,
      @RequestParam(defaultValue = "100") int lines) {
    final StreamingResponseBody body = output -> logs.stream(projectId, sourceId, lines, output);
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
        .header("X-Accel-Buffering", "no")
        .body(body);
  }
}
