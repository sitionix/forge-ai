package com.sitionix.forgeagent.domain.model;

public record GitUpstreamState(
        String ref,
        GitUpstreamRelation relation
) {
}
