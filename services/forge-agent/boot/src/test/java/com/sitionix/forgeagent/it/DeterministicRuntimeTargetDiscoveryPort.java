package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.domain.model.RuntimeTargetCandidate;
import com.sitionix.forgeagent.domain.model.RuntimeTargetStatus;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeProvider;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.RuntimeTargetDiscoveryPort;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DeterministicRuntimeTargetDiscoveryPort implements RuntimeTargetDiscoveryPort {
    @Override
    public List<RuntimeTargetCandidate> discover(
            final SshConnection connection, final ServiceRuntimeProvider provider) {
        return provider == ServiceRuntimeProvider.SYSTEMD
                ? List.of(new RuntimeTargetCandidate("camera.service", "camera.service", provider,
                        RuntimeTargetStatus.RUNNING, null, null, null))
                : List.of(new RuntimeTargetCandidate("camera", "camera", provider,
                        RuntimeTargetStatus.RUNNING, "camera:latest", null, null));
    }
}
