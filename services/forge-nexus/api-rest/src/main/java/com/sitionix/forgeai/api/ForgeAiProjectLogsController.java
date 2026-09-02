package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.*;
import com.sitionix.forgeai.domain.port.AgentLogStream;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectLogs;
import jakarta.validation.Valid;
import java.nio.ByteBuffer;
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
  public List<AgentLogTargetCandidateResponse> discover(
      @PathVariable UUID projectId, @Valid @RequestBody AgentLogDiscoveryRequest request) {
    return logs.discover(projectId, mapper.toCommand(request)).stream()
        .map(mapper::toResponse)
        .toList();
  }

  @PostMapping("/log-sources/validate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void validate(
      @PathVariable UUID projectId, @Valid @RequestBody AgentLogSourceRequest request) {
    logs.validate(projectId, mapper.toCommand(request));
  }

  @GetMapping(path = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<StreamingResponseBody> stream(
      @PathVariable UUID projectId,
      @RequestParam List<UUID> sourceId,
      @RequestParam(defaultValue = "100") int lines) {
    final AgentLogStream upstream = logs.openStream(projectId, sourceId, lines);
    final StreamingResponseBody body =
        output -> {
          try (upstream) {
            final ByteBuffer buffer = ByteBuffer.allocate(8192);
            int read;
            while ((read = upstream.read(buffer)) >= 0) {
              output.write(buffer.array(), 0, read);
              output.flush();
              buffer.clear();
            }
          }
        };
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
        .header("X-Accel-Buffering", "no")
        .body(body);
  }
}
