package com.sitionix.forgeai.application.infrastructure.jarvis;

import java.util.List;

public record JarvisActionView(String action, String description, List<String> targets) {
}
