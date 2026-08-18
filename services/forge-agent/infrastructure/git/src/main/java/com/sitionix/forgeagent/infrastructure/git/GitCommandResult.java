package com.sitionix.forgeagent.infrastructure.git;

record GitCommandResult(int exitCode, String stdout, String stderr) {

    GitCommandResult(final int exitCode, final String output) {
        this(exitCode, output, "");
    }
}
