package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.domain.model.*;import java.nio.file.Path;import java.util.*;import org.junit.jupiter.api.*;

class LocalCliDockerLogAdapterTest {
 @Test void discoveryMapsRuntimeOutputToTypedCandidates(){var executor=new FakeExecutor(List.of("abc\tmission\tUp 2 minutes\timage:1\tancestor\tmission"));var result=new LocalCliDockerLogAdapter(executor).discover(null);assertThat(result).containsExactly(new LogTargetCandidate("abc","mission",LogTargetStatus.RUNNING,"image:1","ancestor","mission",false));}
 @Test void validatesThroughTypedDockerInspectArguments(){var executor=new FakeExecutor(List.of());new LocalCliDockerLogAdapter(executor).validate("mission",null,null,null);assertThat(executor.command).containsExactly("docker","container","inspect","mission");}
 @Test void closeTerminatesFollowProcess() throws Exception {Process process=new ProcessBuilder("sh","-c","sleep 30").start();new ProcessLogStream(process).close();assertThat(process.isAlive()).isFalse();}
 static final class FakeExecutor extends TypedProcessExecutor {private final List<String> result;private List<String> command;FakeExecutor(List<String> result){this.result=result;}@Override List<String> output(List<String> command,Path cwd){this.command=command;return result;}}
}
