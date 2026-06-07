package com.sitionix.forgeai.application.infrastructure.jarvis;

import java.util.Map;

public record JarvisIntentView(String action, String target, Map<String, Object> arguments) {
}
