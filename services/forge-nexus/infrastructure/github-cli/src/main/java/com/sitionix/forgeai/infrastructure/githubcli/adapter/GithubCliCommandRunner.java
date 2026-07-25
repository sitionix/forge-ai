package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import java.time.Duration;
import java.util.List;

interface GithubCliCommandRunner {

    GithubCliCommandResult run(List<String> command, Duration timeout);
}
