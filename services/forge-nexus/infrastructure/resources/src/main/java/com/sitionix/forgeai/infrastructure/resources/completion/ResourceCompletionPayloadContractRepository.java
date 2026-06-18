package com.sitionix.forgeai.infrastructure.resources.completion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadFieldContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadValueType;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AgentTicketPayloadType;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ResourceCompletionPayloadContractRepository implements CompletionPayloadContractRepository {

    private static final String CONTRACT_PATTERN = "classpath:completion-payload-contracts/*.json";
    private static final String AGENT_TICKET_PACKAGE = "com.sitionix.forgeai.domain.model.ticket.agentticket.";
    private static final String LANE_COMPLETION_PACKAGE = "com.sitionix.forgeai.domain.model.lanecompletion.";

    private final ObjectMapper objectMapper;
    private Map<String, CompletionPayloadObjectContract> contracts;

    @PostConstruct
    public void init() {
        this.contracts = new LinkedHashMap<>();
        try {
            final Resource[] resources = new PathMatchingResourcePatternResolver().getResources(CONTRACT_PATTERN);
            for (final Resource resource : resources) {
                final CompletionPayloadObjectContract contract =
                        this.objectMapper.readValue(resource.getInputStream(), CompletionPayloadObjectContract.class);
                if (this.contracts.put(contract.payloadType(), contract) != null) {
                    throw new IllegalStateException("Duplicate completion payload contract: " + contract.payloadType());
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to load completion payload contracts", e);
        }
        this.validateRequiredRootContracts();
        this.contracts.values().forEach(this::validateContract);
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

    private void validateRequiredRootContracts() {
        Arrays.stream(AgentTicketPayloadType.values())
                .map(AgentTicketPayloadType::getPayloadClass)
                .map(Class::getSimpleName)
                .filter(payloadType -> !"AnalyzerPayload".equals(payloadType))
                .forEach(this::findByTypeName);
        this.findByTypeName("ApiCompletionEvidence");
    }

    private void validateContract(final CompletionPayloadObjectContract contract) {
        if (contract.payloadType() == null || contract.payloadType().isBlank()) {
            throw new IllegalStateException("Completion payload contract payloadType is required");
        }
        if (contract.description() == null || contract.description().isBlank()) {
            throw new IllegalStateException("Completion payload contract description is required: " + contract.payloadType());
        }
        final Class<?> payloadClass = this.payloadClass(contract.payloadType());
        final Set<String> javaFields = Arrays.stream(payloadClass.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
        final Set<String> contractFields = contract.fields().stream()
                .map(CompletionPayloadFieldContract::name)
                .collect(Collectors.toSet());
        if (!Objects.equals(javaFields, contractFields)) {
            throw new IllegalStateException("Completion payload contract fields do not match Java payload fields: payloadType="
                    + contract.payloadType()
                    + ", javaFields=" + javaFields
                    + ", contractFields=" + contractFields);
        }
        contract.fields().forEach(field -> this.validateField(contract, field));
    }

    private void validateField(final CompletionPayloadObjectContract contract,
                               final CompletionPayloadFieldContract field) {
        if (field.description() == null || field.description().isBlank()) {
            throw new IllegalStateException("Completion payload field description is required: payloadType="
                    + contract.payloadType() + ", field=" + field.name());
        }
        if (field.type() == CompletionPayloadValueType.OBJECT) {
            this.findByTypeName(field.objectType());
        }
        if (field.type() == CompletionPayloadValueType.ARRAY && field.itemType() == CompletionPayloadValueType.OBJECT) {
            this.findByTypeName(field.itemObjectType());
        }
    }

    private Class<?> payloadClass(final String payloadType) {
        return this.tryLoad(AGENT_TICKET_PACKAGE + payloadType)
                .orElseGet(() -> this.tryLoad(LANE_COMPLETION_PACKAGE + payloadType)
                        .orElseThrow(() -> new IllegalStateException("Completion payload Java class not found: " + payloadType)));
    }

    private java.util.Optional<Class<?>> tryLoad(final String className) {
        try {
            return java.util.Optional.of(Class.forName(className));
        } catch (final ClassNotFoundException e) {
            return java.util.Optional.empty();
        }
    }
}
