package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.*;
import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class ProjectLogsController {
  private final LogSourceUseCases logs;
  private final ProjectLogSseService streaming;
  private final ForgeAgentApiMapper mapper;

  @GetMapping("/api/v1/projects/{projectId}/log-sources")
  public List<LogSourceResponse> list(@PathVariable UUID projectId) {
    return logs.list(projectId).stream().map(this.mapper::toResponse).toList();
  }

  @GetMapping("/api/v1/projects/{projectId}/services/{serviceId}/log-sources")
  public List<LogSourceResponse> listForService(@PathVariable UUID projectId, @PathVariable UUID serviceId) {
    return logs.list(projectId, serviceId).stream().map(this.mapper::toResponse).toList();
  }

  @PostMapping("/api/v1/projects/{projectId}/log-sources")
  public ResponseEntity<LogSourceResponse> create(
      @PathVariable UUID projectId, @Valid @RequestBody LogSourceRequest r) {
    var source = logs.create(projectId, this.mapper.toCommand(r));
    return ResponseEntity.status(HttpStatus.CREATED).body(this.mapper.toResponse(source));
  }

  @PutMapping("/api/v1/projects/{projectId}/log-sources/{id}")
  public LogSourceResponse update(
      @PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody LogSourceRequest r) {
    return this.mapper.toResponse(logs.update(projectId, id, this.mapper.toCommand(r)));
  }

  @DeleteMapping("/api/v1/projects/{projectId}/log-sources/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID projectId, @PathVariable UUID id) {
    logs.delete(projectId, id);
  }

  @PostMapping("/api/v1/projects/{projectId}/log-sources/discover")
  public List<LogTargetCandidateResponse> discover(
      @PathVariable UUID projectId, @Valid @RequestBody LogDiscoveryRequest r) {
    return logs.discover(
            projectId, r.connection(), r.sshConnectionId(), r.provider(), r.repositoryId())
        .stream()
        .map(this.mapper::toResponse)
        .toList();
  }

  @PostMapping("/api/v1/projects/{projectId}/log-sources/validate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void validate(@PathVariable UUID projectId, @Valid @RequestBody LogSourceRequest r) {
    logs.validateTarget(projectId, this.mapper.toCommand(r));
  }

  @GetMapping(
      path = "/api/v1/projects/{projectId}/logs/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable UUID projectId,
      @RequestParam List<UUID> sourceId,
      @RequestParam(defaultValue = "100") int lines) {
    return streaming.stream(projectId, sourceId, lines);
  }

}
