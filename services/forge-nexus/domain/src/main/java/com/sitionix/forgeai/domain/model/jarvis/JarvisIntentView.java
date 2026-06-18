package com.sitionix.forgeai.domain.model.jarvis;

import java.util.Map;

public record JarvisIntentView(String action, String target, Map<String, Object> arguments) {
}
