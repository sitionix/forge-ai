package com.sitionix.forgeai.domain.props;

import java.util.Map;

/**
 * Facade port for YAML service properties.
 */
public interface ServicePropertiesProvider {

    Map<String, ServiceConfigView> getServices();
}
