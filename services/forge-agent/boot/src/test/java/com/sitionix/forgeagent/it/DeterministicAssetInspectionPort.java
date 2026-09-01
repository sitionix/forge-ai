package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.domain.model.AssetCapabilities;
import com.sitionix.forgeagent.domain.model.AssetMetrics;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.AssetInspectionPort;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DeterministicAssetInspectionPort implements AssetInspectionPort {
    @Override
    public AssetMetrics metrics(final SshConnection connection) {
        return new AssetMetrics(37.5, List.of(25.0, 50.0), 8192L, 4096L,
                0.5, 0.4, 0.3,
                List.of(new AssetMetrics.DiskMetric("/", 1000L, 400L)),
                List.of(new AssetMetrics.NetworkMetric("eth0", 123L, 456L)),
                3600L, List.of(new AssetMetrics.TemperatureMetric("cpu", 42.0)));
    }

    @Override
    public AssetCapabilities capabilities(final SshConnection connection) {
        return new AssetCapabilities(true, true);
    }
}
