package com.sitionix.forgeai.domain.port;

import java.nio.file.Path;
import java.util.List;

public interface GitRepositoryPort {

    boolean isInsideWorkTree(Path repository);

    String currentBranch(Path repository);

    String headCommit(Path repository);

    String statusPorcelain(Path repository);

    String defaultBranch(Path repository, List<String> branchCandidates);

    boolean refExists(Path repository, String ref);

    boolean isAncestor(Path repository, String ancestorRef, String descendantRef);

    void clone(String cloneUrl, Path targetDirectory);

    void addAll(Path repository);

    void commit(Path repository, String userName, String userEmail, String message);

    void stash(Path repository, String message);

    void fetch(Path repository, String remote, String branch);

    void checkout(Path repository, String branch);

    void pullFastForwardOnly(Path repository, String remote, String branch);
}
