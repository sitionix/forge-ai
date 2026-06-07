package com.sitionix.forgeai.domain.model.jarvis;

import java.util.List;

public record JarvisActionView(String action, String description, List<String> targets) {
}
