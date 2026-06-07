package com.sitionix.forgeai.domain.port;

import java.nio.file.Path;

public interface GitRepositoryPort {

    String currentBranch(Path repository);

    String headCommit(Path repository);

    String statusPorcelain(Path repository);

    boolean refExists(Path repository, String ref);

    boolean isAncestor(Path repository, String ancestorRef, String descendantRef);
}
