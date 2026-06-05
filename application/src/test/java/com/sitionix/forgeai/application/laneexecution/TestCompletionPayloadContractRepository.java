package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

final class TestCompletionPayloadContractRepository implements CompletionPayloadContractRepository {

    private static final Path CONTRACTS_PATH = Path.of(
            "infrastructure",
            "resources",
            "src",
            "main",
            "resources",
            "completion-payload-contracts"
    );

    private final Map<String, CompletionPayloadObjectContract> contracts = new LinkedHashMap<>();

    TestCompletionPayloadContractRepository(final ObjectMapper objectMapper) {
        final Path contractsDirectory = this.resolveContractsDirectory();
        try (Stream<Path> files = Files.list(contractsDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> this.loadContract(objectMapper, path));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load test completion payload contracts", exception);
        }
    }

    @Override
    public CompletionPayloadObjectContract findByType(final Class<?> payloadType) {
        return this.findByTypeName(payloadType.getSimpleName());
    }

    @Override
    public CompletionPayloadObjectContract findByTypeName(final String payloadType) {
        final CompletionPayloadObjectContract contract = this.contracts.get(payloadType);
        if (contract == null) {
            throw new IllegalArgumentException("Completion payload contract not found for payloadType=" + payloadType);
        }
        return contract;
    }

    private Path resolveContractsDirectory() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            final Path candidate = current.resolve(CONTRACTS_PATH);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Completion payload contracts directory not found: " + CONTRACTS_PATH);
    }

    private void loadContract(final ObjectMapper objectMapper, final Path path) {
        try {
            final CompletionPayloadObjectContract contract =
                    objectMapper.readValue(path.toFile(), CompletionPayloadObjectContract.class);
            this.contracts.put(contract.payloadType(), contract);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load completion payload contract: " + path, exception);
        }
    }
}
