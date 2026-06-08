package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import org.springframework.stereotype.Component;

@Component
class ApiGenerationCommand {

    String body(final String generationName) {
        return "/generate --name \"" + generationName + "\"";
    }
}
