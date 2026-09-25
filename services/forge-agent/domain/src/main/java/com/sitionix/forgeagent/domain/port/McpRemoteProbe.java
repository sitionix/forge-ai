package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpProbeReport;
import java.net.URI;

/** Protocol boundary: decrypted bytes are scoped to one call and never returned. */
public interface McpRemoteProbe {
    McpProbeReport probe(URI endpoint, McpAuthType authType, byte[] credential);
}
