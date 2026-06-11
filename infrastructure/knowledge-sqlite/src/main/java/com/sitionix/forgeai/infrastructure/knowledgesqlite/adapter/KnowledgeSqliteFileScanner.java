package com.sitionix.forgeai.infrastructure.knowledgesqlite.adapter;

import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeFileEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.config.KnowledgeSqliteProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class KnowledgeSqliteFileScanner {

    private final KnowledgeSqliteProperties properties;

    public KnowledgeSqliteScanResult scan(final String sourceId,
                                          final String sourcePath,
                                          final Path root,
                                          final String indexedAt) {
        final AtomicInteger skipped = new AtomicInteger();
        if (!Files.isDirectory(root)) {
            return new KnowledgeSqliteScanResult(List.of(), 0);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            final List<KnowledgeFileEntity> files = paths
                    .filter(Files::isRegularFile)
                    .map(path -> this.file(sourceId, sourcePath, root, path, indexedAt, skipped))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .toList();
            return new KnowledgeSqliteScanResult(files, skipped.get());
        } catch (IOException exception) {
            return new KnowledgeSqliteScanResult(List.of(), skipped.incrementAndGet());
        }
    }

    private java.util.Optional<KnowledgeFileEntity> file(final String sourceId,
                                                        final String sourcePath,
                                                        final Path root,
                                                        final Path path,
                                                        final String indexedAt,
                                                        final AtomicInteger skipped) {
        try {
            final Path relativePath = root.relativize(path).normalize();
            final String normalizedRelativePath = relativePath.toString().replace('\\', '/');
            if (!this.shouldInclude(relativePath, normalizedRelativePath)) {
                skipped.incrementAndGet();
                return java.util.Optional.empty();
            }
            final long size = Files.size(path);
            if (size > this.properties.getMaxFileSizeBytes() || this.isBinary(path)) {
                skipped.incrementAndGet();
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new KnowledgeFileEntity(
                    null,
                    sourceId,
                    sourcePath,
                    path.toRealPath().toString(),
                    normalizedRelativePath,
                    this.extension(path),
                    size,
                    this.sha256(path),
                    Files.getLastModifiedTime(path).toInstant().toString(),
                    indexedAt,
                    null,
                    null,
                    "[]",
                    "{}"
            ));
        } catch (IOException exception) {
            skipped.incrementAndGet();
            return java.util.Optional.empty();
        }
    }

    private boolean shouldInclude(final Path relativePath, final String normalizedRelativePath) {
        for (final Path part : relativePath) {
            if (this.properties.getExcludedDirNames().contains(part.toString())) {
                return false;
            }
        }
        return this.properties.getIncludePatterns().stream().anyMatch(pattern -> this.matcher(pattern).matches(Path.of(normalizedRelativePath)));
    }

    private boolean isBinary(final Path path) throws IOException {
        final byte[] bytes = Files.readAllBytes(path);
        final int sampleSize = Math.min(bytes.length, 8192);
        for (int index = 0; index < sampleSize; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private String sha256(final Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String extension(final Path path) {
        final String fileName = path.getFileName().toString();
        if ("pom.xml".equals(fileName)) {
            return ".xml";
        }
        final int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index);
    }

    private PathMatcher matcher(final String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }
}
