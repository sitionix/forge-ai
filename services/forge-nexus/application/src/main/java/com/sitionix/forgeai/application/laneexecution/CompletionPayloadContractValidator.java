package com.sitionix.forgeai.application.laneexecution;

import com.sitionix.forgeai.domain.model.lanecompletion.ScopeMismatchException;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionOutputContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadFieldContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadValueType;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompletionPayloadContractValidator {

    private final CompletionPayloadContractBuilder completionPayloadContractBuilder;
    private final CompletionPayloadContractRepository completionPayloadContractRepository;

    public void validate(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        final CompletionPayloadContract contract = this.completionPayloadContractBuilder.build(lane);
        this.validateTopLevel(completionPayload, contract);
        this.validateOutputs(lane, this.requireList(completionPayload, "outputs"), contract.outputs());
        if (contract.apiEvidenceRequired()) {
            this.validateObject(
                    this.requireMap(completionPayload, "apiEvidence"),
                    contract.apiEvidence(),
                    "apiEvidence"
            );
        }
        if (contract.report() != null) {
            this.validateObject(
                    this.requireMap(completionPayload, "report"),
                    contract.report(),
                    "report"
            );
        }
    }

    private void validateTopLevel(final Map<String, Object> completionPayload,
                                  final CompletionPayloadContract contract) {
        if (completionPayload == null) {
            throw new IllegalArgumentException("Completion payload must be an object");
        }
        final Set<String> allowed = new LinkedHashSet<>();
        allowed.add("outputs");
        if (contract.apiEvidenceRequired()) {
            allowed.add("apiEvidence");
        }
        if (contract.report() != null) {
            allowed.add("report");
        }
        final Set<String> actual = new LinkedHashSet<>(completionPayload.keySet());
        if (!allowed.containsAll(actual)) {
            actual.removeAll(allowed);
            throw new IllegalArgumentException("Unknown completion payload fields: " + actual);
        }
    }

    private void validateOutputs(final ReadyToStartLane lane,
                                 final List<?> outputs,
                                 final List<CompletionOutputContract> outputContracts) {
        for (final Object rawOutput : outputs) {
            if (!(rawOutput instanceof Map<?, ?> output)) {
                throw new IllegalArgumentException("Completion output must be an object");
            }
            final String agent = this.requiredString(output, "agent", "outputs");
            final String scope = this.requiredString(output, "scope", "outputs");
            final CompletionOutputContract contract = outputContracts.stream()
                    .filter(candidate -> Objects.equals(candidate.agent(), agent))
                    .filter(candidate -> Objects.equals(candidate.scope(), scope))
                    .findFirst()
                    .orElseGet(() -> this.requireKnownOutput(lane, outputContracts, agent, scope));
            this.validateOutputFields(output);
            final boolean required = this.requiredBoolean(output, "required", "outputs");
            if (!required) {
                continue;
            }
            final Map<?, ?> payload = this.requireMap(output, "payload", "outputs");
            this.validateObject(payload, contract.payload(), "outputs.payload");
            this.validatePayloadScope(payload, contract);
        }
    }

    private CompletionOutputContract requireKnownOutput(final ReadyToStartLane lane,
                                                        final List<CompletionOutputContract> outputContracts,
                                                        final String agent,
                                                        final String scope) {
        final CompletionOutputContract targetWithSameAgent = outputContracts.stream()
                .filter(candidate -> Objects.equals(candidate.agent(), agent))
                .findFirst()
                .orElse(null);
        if (targetWithSameAgent != null) {
            throw new ScopeMismatchException("Completion output scope mismatch: sourceLaneId=" + lane.getLaneId()
                    + ", sourceAgent=" + lane.getAgent().getId()
                    + ", targetAgent=" + targetWithSameAgent.agent()
                    + ", expectedScope=" + targetWithSameAgent.scope()
                    + ", actualScope=" + scope);
        }
        throw new IllegalArgumentException("Unknown completion output target: agent=" + agent + ", scope=" + scope);
    }

    private void validateOutputFields(final Map<?, ?> output) {
        final Set<String> allowed = Set.of("agent", "scope", "required", "payload");
        final Set<String> actual = output.keySet().stream()
                .map(Object::toString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!allowed.containsAll(actual)) {
            actual.removeAll(allowed);
            throw new IllegalArgumentException("Unknown completion output fields: " + actual);
        }
    }

    private void validatePayloadScope(final Map<?, ?> payload,
                                      final CompletionOutputContract contract) {
        if (!this.hasField(contract.payload(), "scope")) {
            return;
        }
        final Object actual = payload.get("scope");
        if (!Objects.equals(contract.payloadScope(), actual)) {
            throw new ScopeMismatchException("Completion payload scope mismatch: expected="
                    + contract.payloadScope()
                    + ", actual=" + actual);
        }
    }

    private boolean hasField(final CompletionPayloadObjectContract objectContract, final String fieldName) {
        return objectContract.fields().stream()
                .anyMatch(field -> fieldName.equals(field.name()));
    }

    private void validateObject(final Map<?, ?> rawObject,
                                final CompletionPayloadObjectContract objectContract,
                                final String path) {
        final Set<String> allowed = objectContract.fields().stream()
                .map(CompletionPayloadFieldContract::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<String> actual = rawObject.keySet().stream()
                .map(Object::toString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!allowed.containsAll(actual)) {
            actual.removeAll(allowed);
            throw new IllegalArgumentException("Unknown fields at " + path + ": " + actual);
        }
        for (final CompletionPayloadFieldContract field : objectContract.fields()) {
            final Object value = rawObject.get(field.name());
            if (value == null) {
                if (field.required()) {
                    throw new IllegalArgumentException("Missing required field: " + path + "." + field.name());
                }
                continue;
            }
            this.validateValue(value, field, path + "." + field.name());
        }
    }

    private void validateValue(final Object value,
                               final CompletionPayloadFieldContract field,
                               final String path) {
        switch (field.type()) {
            case STRING -> this.requireType(value instanceof CharSequence, path, "string");
            case BOOLEAN -> this.requireType(value instanceof Boolean, path, "boolean");
            case INTEGER -> this.requireType(value instanceof Integer || value instanceof Long, path, "integer");
            case NUMBER -> this.requireType(value instanceof Number, path, "number");
            case OBJECT -> this.validateObject(
                    this.asMap(value, path),
                    this.completionPayloadContractRepository.findByTypeName(field.objectType()),
                    path
            );
            case ARRAY -> this.validateArray(value, field, path);
        }
    }

    private void validateArray(final Object value,
                               final CompletionPayloadFieldContract field,
                               final String path) {
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException("Invalid field type at " + path + ": expected array");
        }
        final CompletionPayloadValueType itemType = field.itemType();
        if (itemType == null) {
            return;
        }
        int index = 0;
        for (final Object item : collection) {
            final CompletionPayloadFieldContract itemField = new CompletionPayloadFieldContract(
                    path + "[" + index + "]",
                    itemType,
                    true,
                    "array item",
                    null,
                    field.itemObjectType(),
                    null
            );
            this.validateValue(item, itemField, path + "[" + index + "]");
            index++;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> asMap(final Object value, final String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Invalid field type at " + path + ": expected object");
        }
        return map;
    }

    private List<?> requireList(final Map<String, Object> source, final String field) {
        final Object value = source.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Missing array field: " + field);
        }
        return list;
    }

    private Map<?, ?> requireMap(final Map<String, Object> source, final String field) {
        return this.requireMap(source, field, field);
    }

    private Map<?, ?> requireMap(final Map<?, ?> source, final String field, final String path) {
        final Object value = source.get(field);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Missing object field: " + path + "." + field);
        }
        return map;
    }

    private String requiredString(final Map<?, ?> source, final String field, final String path) {
        final Object value = source.get(field);
        if (!(value instanceof CharSequence text) || text.isEmpty()) {
            throw new IllegalArgumentException("Missing string field: " + path + "." + field);
        }
        return text.toString();
    }

    private boolean requiredBoolean(final Map<?, ?> source, final String field, final String path) {
        final Object value = source.get(field);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("Missing boolean field: " + path + "." + field);
        }
        return bool;
    }

    private void requireType(final boolean condition, final String path, final String expected) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid field type at " + path + ": expected " + expected);
        }
    }
}
