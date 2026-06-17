package com.sitionix.forgeai.domain.props;

import java.util.List;

/**
 * Facade port for YAML agent properties.
 */
public interface AgentPropertiesProvider {

    List<AgentConfigView> getAgents();
}
