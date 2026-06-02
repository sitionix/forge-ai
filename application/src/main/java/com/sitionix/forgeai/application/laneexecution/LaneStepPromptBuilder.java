package com.sitionix.forgeai.application.laneexecution;

import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyPromptConfig;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneStepPromptBuilder {

    private final LaneStrategyPromptConfig laneStrategyPromptConfig;

    public String buildStartPrompt(final ReadyToStartLane lane,
                                   final LaneStrategy strategy,
                                   final Path startContextPath) {
        final StringBuilder builder = new StringBuilder("START_PROMPT\n");
        this.appendSection(builder, "ticketId", Objects.toString(lane.getTicketId(), ""));
        this.appendSection(builder, "ticketKey", Objects.toString(lane.getTicketKey(), ""));
        this.appendSection(builder, "laneId", Objects.toString(lane.getLaneId(), ""));
        this.appendSection(builder, "agentId", Objects.toString(lane.getAgent().getId(), ""));
        this.appendSection(builder, "scope", Objects.toString(lane.getScope(), ""));
        this.appendSection(builder, "strategyId", Objects.toString(strategy.getAgentId(), ""));
        this.appendSection(builder, "strategyVersion", Integer.toString(strategy.getVersion()));
        this.appendSection(builder, "workspaceRoot", this.workspaceRoot());
        this.appendSection(builder, "startContext", this.relativePath(startContextPath));
        this.appendSection(builder, "commonInstructionRefs", this.formatRefs(this.laneStrategyPromptConfig.getCommonInstructionRefs()));
        builder.append("Read the runtime context file before proceeding.");
        return builder.toString().trim();
    }

    public String buildStepPrompt(final LaneStrategyStep step,
                                  final int index,
                                  final int total,
                                  final Path runtimeStepPath) {
        final StringBuilder builder = new StringBuilder("STEP_PROMPT\n");
        this.appendSection(builder, "stepIndex", index + "/" + total);
        this.appendSection(builder, "stepId", step.getId());
        this.appendSection(builder, "stepTitle", step.getTitle());
        this.appendSection(builder, "runtimeStepFile", this.relativePath(runtimeStepPath));
        builder.append("Return one valid LANE_STEP_DONE result for this step.");
        return builder.toString().trim();
    }

    public String buildCorrectionPrompt(final String stepId, final Path runtimeStepPath) {
        return ("CORRECTION_PROMPT\n"
                + "Your previous response did not contain a valid LANE_STEP_DONE result for stepId=" + stepId + ".\n"
                + "Read the active step instruction again:\n"
                + this.relativePath(runtimeStepPath) + "\n"
                + "Return one valid LANE_STEP_DONE result.").trim();
    }

    public List<String> commonInstructionRefs() {
        return List.copyOf(this.laneStrategyPromptConfig.getCommonInstructionRefs());
    }

    private void appendSection(final StringBuilder builder, final String label, final String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        builder.append(label).append(":\n").append(value.trim()).append("\n\n");
    }

    private String formatRefs(final List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        for (final String ref : refs) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("- ").append(ref);
        }
        return builder.toString();
    }

    private String relativePath(final Path path) {
        if (path == null) {
            return "";
        }
        final Path normalizedPath = path.toAbsolutePath().normalize();
        final Path workspaceRoot = Path.of(this.workspaceRoot()).toAbsolutePath().normalize();
        if (normalizedPath.startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(normalizedPath).toString();
        }
        return path.toString();
    }

    private String workspaceRoot() {
        final String envWorkspaceRoot = System.getenv("WORKSPACE_ROOT");
        if (envWorkspaceRoot != null && !envWorkspaceRoot.isBlank()) {
            return Path.of(envWorkspaceRoot).toAbsolutePath().normalize().toString();
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().toString();
    }
}
