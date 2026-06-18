package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeInventoryBuildRequest(List<String> sourceIds, List<String> groups, Boolean force) {
}
