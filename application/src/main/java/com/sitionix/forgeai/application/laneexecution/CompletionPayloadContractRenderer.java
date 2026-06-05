package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionOutputContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadFieldContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadValueType;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompletionPayloadContractRenderer {

    private final ObjectMapper objectMapper;
    private final CompletionPayloadContractRepository completionPayloadContractRepository;

    public String render(final CompletionPayloadContract contract) {
        final StringBuilder builder = new StringBuilder()
                .append("evidence.completionPayload must match this contract.\n\n")
                .append("Completion payload JSON template:\n")
                .append(this.toPrettyJson(this.template(contract)))
                .append("\n\n")
                .append("Completion payload field contract:\n")
                .append(this.toPrettyJson(this.describe(contract)))
                .append("\n\n")
                .append("Rules:\n")
                .append("- outputs must be an array. Use [] when this lane produces no downstream work.\n")
                .append("- each output agent and scope must exactly match a listed produced lane.\n")
                .append("- required=false marks that produced lane as not needed.\n")
                .append("- required=true requires payload to satisfy the listed payload contract.\n")
                .append("- do not add fields not listed in the contract.\n")
                .append("- do not invent agents, scopes, field names, payload shapes, or evidence.");
        return builder.toString();
    }

    private Map<String, Object> template(final CompletionPayloadContract contract) {
        final Map<String, Object> template = new LinkedHashMap<>();
        template.put("outputs", contract.outputs().stream()
                .map(this::outputTemplate)
                .toList());
        if (contract.apiEvidenceRequired()) {
            template.put("apiEvidence", this.objectTemplate(contract.apiEvidence()));
        }
        if (contract.report() != null) {
            template.put("report", this.objectTemplate(contract.report()));
        }
        return template;
    }

    private Map<String, Object> outputTemplate(final CompletionOutputContract output) {
        final Map<String, Object> template = new LinkedHashMap<>();
        template.put("agent", output.agent());
        template.put("scope", output.scope());
        template.put("required", output.required());
        template.put("payload", this.objectTemplate(output.payload()));
        return template;
    }

    private Map<String, Object> objectTemplate(final CompletionPayloadObjectContract objectContract) {
        final Map<String, Object> template = new LinkedHashMap<>();
        for (final CompletionPayloadFieldContract field : objectContract.fields()) {
            template.put(field.name(), this.fieldTemplate(field));
        }
        return template;
    }

    private Object fieldTemplate(final CompletionPayloadFieldContract field) {
        return switch (field.type()) {
            case STRING -> "...";
            case BOOLEAN -> true;
            case INTEGER -> 0;
            case NUMBER -> 0.0;
            case OBJECT -> this.objectTemplate(this.completionPayloadContractRepository.findByTypeName(field.objectType()));
            case ARRAY -> List.of(this.arrayItemTemplate(field));
        };
    }

    private Object arrayItemTemplate(final CompletionPayloadFieldContract field) {
        final CompletionPayloadValueType itemType = field.itemType();
        if (itemType == null) {
            return Map.of();
        }
        return switch (itemType) {
            case STRING -> "...";
            case BOOLEAN -> true;
            case INTEGER -> 0;
            case NUMBER -> 0.0;
            case OBJECT -> this.objectTemplate(this.completionPayloadContractRepository.findByTypeName(field.itemObjectType()));
            case ARRAY -> List.of();
        };
    }

    private Map<String, Object> describe(final CompletionPayloadContract contract) {
        final Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("outputs", contract.outputs().stream()
                .map(this::outputDescriptor)
                .toList());
        if (contract.apiEvidenceRequired()) {
            descriptor.put("apiEvidence", this.objectDescriptor(contract.apiEvidence()));
        }
        if (contract.report() != null) {
            descriptor.put("report", this.objectDescriptor(contract.report()));
        }
        return descriptor;
    }

    private Map<String, Object> outputDescriptor(final CompletionOutputContract output) {
        final Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("agent", output.agent());
        descriptor.put("scope", output.scope());
        descriptor.put("required", output.required());
        descriptor.put("payload", this.objectDescriptor(output.payload()));
        return descriptor;
    }

    private Map<String, Object> objectDescriptor(final CompletionPayloadObjectContract objectContract) {
        final Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("payloadType", objectContract.payloadType());
        descriptor.put("description", objectContract.description());
        descriptor.put("fields", objectContract.fields().stream()
                .map(this::fieldDescriptor)
                .toList());
        return descriptor;
    }

    private Map<String, Object> fieldDescriptor(final CompletionPayloadFieldContract field) {
        final Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", field.name());
        descriptor.put("type", field.type());
        descriptor.put("required", field.required());
        descriptor.put("description", field.description());
        if (field.itemType() != null) {
            descriptor.put("itemType", field.itemType());
        }
        if (field.objectType() != null) {
            descriptor.put("objectType", field.objectType());
        }
        if (field.itemObjectType() != null) {
            descriptor.put("itemObjectType", field.itemObjectType());
        }
        return descriptor;
    }

    private String toPrettyJson(final Object value) {
        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to render completion payload contract", e);
        }
    }
}
