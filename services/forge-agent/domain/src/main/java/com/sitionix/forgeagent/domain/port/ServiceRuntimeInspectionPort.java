package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.*;
public interface ServiceRuntimeInspectionPort {
  ServiceRuntimeView inspect(ProjectService service, SshConnection connection);
}
