package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ApiLaneContractResult;
import com.app_afesox.fgaisox.api_first.dto.ApiLaneGeneratedArtifact;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidenceDependency;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload;
import java.util.List;
import java.util.Objects;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface ApiLaneEvidencePayloadApiMapper {

    @Mapping(target = "prUrl", source = "prUrl")
    @Mapping(target = "repo", source = "repo")
    @Mapping(target = "dependencies", source = "contracts", qualifiedByName = "toDependencies")
    ApiLaneEvidencePayload asApiLaneEvidencePayload(CompleteApiLaneRequest source);

    default ApiLaneEvidencePayload asApiLaneEvidencePayloadOrEmpty(final CompleteApiLaneRequest source) {
        if (source == null) {
            return ApiLaneEvidencePayload.builder()
                    .prUrl(null)
                    .repo(null)
                    .dependencies(List.of())
                    .build();
        }
        return this.asApiLaneEvidencePayload(source);
    }

    @Named("toDependencies")
    default List<ApiLaneEvidenceDependency> toDependencies(final List<ApiLaneContractResult> contracts) {
        if (contracts == null) {
            return List.of();
        }
        return contracts.stream()
                .filter(Objects::nonNull)
                .filter(contract -> contract.getArtifacts() != null)
                .flatMap(contract -> contract.getArtifacts().stream()
                        .map(artifact -> this.asApiLaneEvidenceDependency(contract.getScope(), artifact)))
                .toList();
    }

    default ApiLaneEvidenceDependency asApiLaneEvidenceDependency(final String scope, final ApiLaneGeneratedArtifact source) {
        if (source == null) {
            return ApiLaneEvidenceDependency.builder()
                    .scope(scope)
                    .role(null)
                    .runId(null)
                    .build();
        }
        return ApiLaneEvidenceDependency.builder()
                .scope(scope)
                .role(source.getRole() == null ? null : source.getRole().getValue())
                .runId(source.getRunId())
                .build();
    }
}
