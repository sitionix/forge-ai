package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteQaLeadLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationTestCaseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadUnitTestNoteDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface QaLeadCompletionTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Prepare integration test execution context for \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "integrationTestCases", expression = "java(this.asIntegrationTestCases(source.getIntegrationTestCases()))")
    @Mapping(target = "unitTestNotes", expression = "java(this.asUnitTestNotes(source.getUnitTestNotes()))")
    TestItPayload asTestItPayload(CompleteQaLeadLaneRequestDTO source);

    @Mapping(target = "task", expression = "java(\"Prepare UI test execution context for \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "integrationTestCases", expression = "java(this.asIntegrationTestCases(source.getIntegrationTestCases()))")
    @Mapping(target = "unitTestNotes", expression = "java(this.asUnitTestNotes(source.getUnitTestNotes()))")
    TestUiPayload asTestUiPayload(CompleteQaLeadLaneRequestDTO source);

    default Set<String> asIntegrationTestCases(final List<QaLeadIntegrationTestCaseDTO> source) {
        return source.stream()
                .map(this::asIntegrationTestCase)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    default Set<String> asUnitTestNotes(final List<QaLeadUnitTestNoteDTO> source) {
        return source.stream()
                .map(this::asUnitTestNote)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    default String asIntegrationTestCase(final QaLeadIntegrationTestCaseDTO source) {
        final StringJoiner joiner = new StringJoiner(" | ");
        joiner.add("title=" + source.getTitle());
        joiner.add("flow=" + source.getFlow().getMethod() + " " + source.getFlow().getPath());
        joiner.add("given=" + String.join(", ", source.getGiven()));
        joiner.add("when=" + String.join(", ", source.getWhen()));
        joiner.add("then=" + String.join(", ", source.getThen()));
        if (Objects.nonNull(source.getDataChecks()) && !source.getDataChecks().isEmpty()) {
            joiner.add("dataChecks=" + source.getDataChecks().stream()
                    .map(value -> value.getTarget() + " -> " + value.getExpectation())
                    .collect(java.util.stream.Collectors.joining("; ")));
        }
        joiner.add("priority=" + source.getPriority());
        return joiner.toString();
    }

    default String asUnitTestNote(final QaLeadUnitTestNoteDTO source) {
        return source.getTarget() + " :: " + source.getNote();
    }
}
