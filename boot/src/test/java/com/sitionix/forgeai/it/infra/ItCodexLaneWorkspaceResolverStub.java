package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.domain.model.codex.CodexLaneWorkspace;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.usecase.ResolveCodexLaneWorkspace;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Primary
@Profile("it")
public class ItCodexLaneWorkspaceResolverStub implements ResolveCodexLaneWorkspace {

    @Override
    public CodexLaneWorkspace resolve(final ReadyToStartLane lane) {
        final String workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
        return new CodexLaneWorkspace(workspace, List.of(workspace));
    }
}
