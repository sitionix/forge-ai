package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.QaLeadDataCheckDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadIntegrationTestCaseDTO;
import com.app_afesox.fgaisox.api_first.dto.QaLeadUnitTestNoteDTO;
import java.util.Objects;
import java.util.StringJoiner;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

@Component
public class QaLeadCompletionTicketPayloadApiMapperSupport {

    @Named("asIntegrationTestCase")
    public String asIntegrationTestCase(final QaLeadIntegrationTestCaseDTO source) {
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

    @Named("asUnitTestNote")
    public String asUnitTestNote(final QaLeadUnitTestNoteDTO source) {
        return source.getTarget() + " :: " + source.getNote();
    }
}
