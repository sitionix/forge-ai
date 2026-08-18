package com.sitionix.forgeagent.infrastructure.git;

import java.util.List;

interface GitCommandRunner {

    GitCommandResult run(List<String> command, GitCommandExecutionPolicy policy);
}
