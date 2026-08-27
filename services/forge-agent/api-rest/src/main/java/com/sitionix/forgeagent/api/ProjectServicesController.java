package com.sitionix.forgeagent.api;
import com.sitionix.forgeagent.api.dto.*;
import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeTarget;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class ProjectServicesController {
 private final ProjectServiceUseCases services;
 @GetMapping("/api/v1/projects/{projectId}/services") public List<ProjectServiceResponse> list(@PathVariable UUID projectId){return services.list(projectId).stream().map(this::response).toList();}
 @PostMapping("/api/v1/projects/{projectId}/services") public ResponseEntity<ProjectServiceResponse> create(@PathVariable UUID projectId,@Valid @RequestBody ProjectServiceRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(response(services.create(projectId,command(r))));}
 @GetMapping("/api/v1/projects/{projectId}/services/{serviceId}") public ProjectServiceResponse get(@PathVariable UUID projectId,@PathVariable UUID serviceId){return response(services.get(projectId,serviceId));}
 @PutMapping("/api/v1/projects/{projectId}/services/{serviceId}") public ProjectServiceResponse update(@PathVariable UUID projectId,@PathVariable UUID serviceId,@Valid @RequestBody ProjectServiceRequest r){return response(services.update(projectId,serviceId,command(r)));}
 @DeleteMapping("/api/v1/projects/{projectId}/services/{serviceId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID projectId,@PathVariable UUID serviceId){services.delete(projectId,serviceId);}
 @GetMapping("/api/v1/projects/{projectId}/services/{serviceId}/runtime") public ServiceRuntimeResponse runtime(@PathVariable UUID projectId,@PathVariable UUID serviceId){var v=services.runtime(projectId,serviceId);return new ServiceRuntimeResponse(v.status(),v.provider(),v.connection(),v.targetIdentity(),v.startedAt(),v.uptime(),v.metadata(),v.health());}
 private SaveProjectServiceCommand command(ProjectServiceRequest r){var t=r.runtimeTarget();return new SaveProjectServiceCommand(r.name(),r.repositoryId(),new ServiceRuntimeTarget(t.connection(),t.sshConnectionId(),t.provider(),t.container(),t.unit()));}
 private ProjectServiceResponse response(com.sitionix.forgeagent.domain.model.ProjectService s){return new ProjectServiceResponse(s.id(),s.projectId(),s.name(),s.repositoryId(),s.runtimeTarget(),s.createdAt(),s.updatedAt());}
}
