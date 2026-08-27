package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.ProjectService;
import java.util.*;
public interface ProjectServiceRepository {
  List<ProjectService> findByProjectId(UUID projectId);
  Optional<ProjectService> findById(UUID id);
  ProjectService save(ProjectService service);
  void delete(ProjectService service);
}
