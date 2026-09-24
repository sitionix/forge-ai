package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryProperties;
import com.sitionix.forgeagent.domain.model.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolatedWorkspaceTest {
    @TempDir Path root;
    LocalProjectWorkspaceAdapter adapter() throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Path projects=Files.createDirectory(root.resolve("forge-projects"));
        Files.setAttribute(projects,"unix:mode",02750);
        return new LocalProjectWorkspaceAdapter(new ForgeRootResolver(root),new RuntimeBoundaryProperties(true,"/missing-unused"));
    }
    @Test void onlyCheckoutContentsAreGroupWritable() throws Exception {
        var adapter=adapter();
        var attempt=adapter.prepareCloneAttempt(UUID.randomUUID(),new ProjectRepositoryWorkspaceReference(UUID.randomUUID(),"repo"));
        assertThat(((Number)Files.getAttribute(attempt.stagingPath(),"unix:mode")).intValue() & 07777).isEqualTo(02770);
        assertThat(((Number)Files.getAttribute(attempt.stagingPath().getParent(),"unix:mode")).intValue() & 07777).isEqualTo(02750);
        assertThat(((Number)Files.getAttribute(attempt.finalPath().getParent(),"unix:mode")).intValue() & 07777).isEqualTo(02750);
    }
    @Test void symlinkProjectCannotRedirectBackendMkdir() throws Exception {
        var adapter=adapter();
        var id=UUID.randomUUID(); var outside=Files.createDirectory(root.resolve("control"));
        Files.createSymbolicLink(root.resolve("forge-projects").resolve(id.toString()),outside);
        assertThatThrownBy(() -> adapter.prepareCloneAttempt(id,new ProjectRepositoryWorkspaceReference(UUID.randomUUID(),"repo")))
            .isInstanceOf(RuntimeException.class);
        assertThat(outside.resolve(".forge-clone-attempts")).doesNotExist();
    }
    @Test void cleanupUsesOpenedDirectoryAfterRuntimeSwapsPathToControlSymlink() throws Exception {
        var original=Files.createDirectory(root.resolve("runtime"));
        Files.writeString(original.resolve("secret"),"disposable");
        var control=Files.createDirectory(root.resolve("control"));
        var secret=Files.writeString(control.resolve("secret"),"synthetic-control");
        try (var stream=Files.newDirectoryStream(original)) {
            assertThat(stream).isInstanceOf(SecureDirectoryStream.class);
            Files.move(original,root.resolve("parked"));
            Files.createSymbolicLink(original,control);
            LocalProjectWorkspaceAdapter.deleteSecureContents((SecureDirectoryStream<Path>)stream);
        }
        assertThat(secret).hasContent("synthetic-control");
        assertThat(root.resolve("parked/secret")).doesNotExist();
    }
    @Test void cleanupNeverFollowsNestedSymlinkToControlState() throws Exception {
        var adapter=adapter();
        var attempt=adapter.prepareCloneAttempt(UUID.randomUUID(),new ProjectRepositoryWorkspaceReference(UUID.randomUUID(),"repo"));
        var outside=Files.createDirectory(root.resolve("control")); var secret=Files.writeString(outside.resolve("secret"),"synthetic");
        Files.createDirectories(attempt.stagingPath().resolve("nested"));
        Files.createSymbolicLink(attempt.stagingPath().resolve("nested/escape"),outside);
        adapter.cleanupCloneAttempt(attempt);
        assertThat(secret).hasContent("synthetic");
        assertThat(attempt.stagingPath()).doesNotExist();
    }
}
