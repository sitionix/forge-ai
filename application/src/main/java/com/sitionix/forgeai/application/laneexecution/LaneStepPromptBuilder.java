package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyPromptConfig;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneStepPromptBuilder {

    private static final String RESULT_CONTRACT = """
            Return exactly one JSON object with no markdown fences and no text before or after it:
            {
              "type": "LANE_STEP_DONE",
              "stepId": "<active step id>",
              "summary": "<non-empty summary>",
              "evidence": {}
            }

            Rules:
            - type must equal LANE_STEP_DONE
            - stepId must equal the active step id
            - summary must be a non-empty string
            - evidence must be a JSON object
            - nested evidence objects and arrays are allowed
            - forbidden top-level fields: status, failed, blocked, skipped, needsFix, error
            - no extra top-level fields
            """;

    private final LaneStrategyPromptConfig laneStrategyPromptConfig;
    private final InstructionRepository instructionRepository;
    private final ObjectMapper objectMapper;

    public String buildStartPrompt(final ReadyToStartLane lane,
                                   final LaneStrategy strategy,
                                   final AgentExecutionInput<AgentTicketPayload> input) {
        final StringBuilder prompt = new StringBuilder()
                .append("START_PROMPT\n\n")
                .append("Execution metadata:\n")
                .append("- ticketId: ").append(lane.getTicketId()).append('\n')
                .append("- ticketKey: ").append(lane.getTicketKey()).append('\n')
                .append("- laneId: ").append(lane.getLaneId()).append('\n')
                .append("- agentId: ").append(lane.getAgent().getId()).append('\n')
                .append("- scope: ").append(lane.getScope()).append('\n')
                .append("- strategyId: ").append(strategy.getAgentId()).append('\n')
                .append("- strategyVersion: ").append(strategy.getVersion()).append('\n')
                .append("- sessionMode: ").append(strategy.getSessionMode()).append('\n')
                .append('\n')
                .append("Task payloads:\n")
                .append(this.renderTasks(input))
                .append('\n')
                .append('\n')
                .append("Scope context:\n")
                .append(this.renderScopeContext(input))
                .append('\n')
                .append('\n')
                .append("Common instructions:\n")
                .append(this.renderResolvedInstructions(this.laneStrategyPromptConfig.getCommonInstructionRefs()))
                .append('\n')
                .append('\n')
                .append("JSON result contract:\n")
                .append(RESULT_CONTRACT.trim());
        return prompt.toString().trim();
    }

    public String buildStepPrompt(final ReadyToStartLane lane,
                                  final LaneStrategy strategy,
                                  final LaneStrategyStep step,
                                  final AgentExecutionInput<AgentTicketPayload> input,
                                  final int stepIndex,
                                  final int totalSteps) {
        final StringBuilder prompt = new StringBuilder()
                .append("STEP_PROMPT\n\n")
                .append("Current step:\n")
                .append("- stepIndex: ").append(stepIndex).append('\n')
                .append("- totalSteps: ").append(totalSteps).append('\n')
                .append("- stepId: ").append(step.getId()).append('\n')
                .append("- stepTitle: ").append(step.getTitle()).append('\n')
                .append('\n')
                .append("Assigned lane context:\n")
                .append("- ticketId: ").append(lane.getTicketId()).append('\n')
                .append("- ticketKey: ").append(lane.getTicketKey()).append('\n')
                .append("- laneId: ").append(lane.getLaneId()).append('\n')
                .append("- agentId: ").append(lane.getAgent().getId()).append('\n')
                .append("- scope: ").append(lane.getScope()).append('\n')
                .append('\n')
                .append("Task payloads:\n")
                .append(this.renderTasks(input))
                .append('\n')
                .append('\n')
                .append("Scope context:\n")
                .append(this.renderScopeContext(input))
                .append('\n')
                .append('\n')
                .append("Active step instructions:\n")
                .append(this.renderResolvedInstructions(step.getInstructionRefs()))
                .append('\n');
        if (stepIndex == totalSteps) {
            prompt.append('\n')
                    .append("Final-step completion payload contract:\n")
                    .append(this.finalCompletionPayloadContract(lane.getAgent()))
                    .append('\n');
        }
        prompt.append('\n')
                .append("Execute only this step. Return only the JSON object.");
        return prompt.toString().trim();
    }

    public String buildCorrectionPrompt(final ReadyToStartLane lane,
                                        final LaneStrategyStep step,
                                        final String validationError,
                                        final boolean finalStep) {
        final StringBuilder prompt = new StringBuilder()
                .append("CORRECTION_PROMPT\n\n")
                .append("Active step id: ").append(step.getId()).append('\n')
                .append("Active step title: ").append(step.getTitle()).append('\n')
                .append("Validation error: ").append(Objects.toString(validationError, "invalid response")).append('\n')
                .append('\n')
                .append("Return only one corrected JSON object. No prose. No markdown fences.");
        if (finalStep) {
            prompt.append('\n')
                    .append('\n')
                    .append("Final-step completion payload contract:\n")
                    .append(this.finalCompletionPayloadContract(lane.getAgent()));
        }
        return prompt.toString().trim();
    }

    private String renderResolvedInstructions(final Iterable<String> refs) {
        final StringBuilder builder = new StringBuilder();
        for (final String ref : refs) {
            builder.append("### ").append(ref).append('\n')
                    .append(this.instructionRepository.findInstructionTextByRef(ref).trim())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private String renderTasks(final AgentExecutionInput<AgentTicketPayload> input) {
        if (input == null || input.getTasks() == null || input.getTasks().isEmpty()) {
            return "[]";
        }
        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input.getTasks());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to render task payloads", e);
        }
    }

    private String renderScopeContext(final AgentExecutionInput<AgentTicketPayload> input) {
        if (input == null || input.getScope() == null) {
            return "{}";
        }
        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input.getScope());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to render scope context", e);
        }
    }

    private String finalCompletionPayloadContract(final Agent agent) {
        return switch (agent) {
            case ANALYZER -> """
                    evidence.completionPayload must be:
                    {
                      "architectHandoff": {
                        "scope": "<lane scope>",
                        "requirements": [],
                        "constraints": [],
                        "nonGoals": [],
                        "risks": [],
                        "dependencies": []
                      },
                      "qaLeadHandoff": {
                        "scope": "<lane scope>",
                        "requirements": [],
                        "constraints": [],
                        "nonGoals": [],
                        "risks": [],
                        "dependencies": [],
                        "qualityFocus": [],
                        "edgeConsiderations": []
                      }
                    }
                    """.trim();
            case ARCHITECT -> """
                    evidence.completionPayload must be:
                    {
                      "implementationScope": "<lane scope>",
                      "implementationHandoff": {
                        "scope": "<lane scope>",
                        "task": "...",
                        "summary": "...",
                        "requirements": [],
                        "constraints": [],
                        "nonGoals": [],
                        "architectureDecision": "...",
                        "dependencies": [],
                        "acceptanceNotes": [],
                        "risks": []
                      },
                      "apiRequired": true,
                      "apiRequest": {
                        "required": true,
                        "reason": "...",
                        "scope": "GLOBAL",
                        "summary": "...",
                        "operations": [],
                        "consumers": [],
                        "notes": []
                      },
                      "eventRequired": true,
                      "eventRequest": {
                        "required": true,
                        "reason": "...",
                        "scope": "GLOBAL",
                        "summary": "...",
                        "eventName": "...",
                        "payloadFields": [],
                        "consumers": [],
                        "notes": []
                      }
                    }
                    If apiRequired is false, omit apiRequest.
                    If eventRequired is false, omit eventRequest.
                    """.trim();
            case API -> """
                    evidence.completionPayload must be:
                    {
                      "summary": "...",
                      "prUrl": "https://github.com/owner/repo/pull/123",
                      "repo": "owner/repo",
                      "contracts": [
                        {
                          "scope": "<implementation scope>",
                          "method": "GET",
                          "path": "/resource",
                          "operationId": "operationId",
                          "notes": [],
                          "artifacts": [
                            {
                              "kind": "MAVEN|NPM|OTHER",
                              "role": "BACKEND_CONTRACT|FRONTEND_CONTRACT|OTHER",
                              "dependency": "...",
                              "runId": 123456,
                              "notes": []
                            }
                          ]
                        }
                      ]
                    }
                    """.trim();
            case QA_LEAD -> """
                    evidence.completionPayload must be:
                    {
                      "scope": "<lane scope>",
                      "unitTestRequired": true,
                      "testUnitPayload": {...},
                      "integrationTestRequired": true,
                      "testItPayload": {...},
                      "uiTestRequired": false
                    }
                    Use payload objects that match the current lane facts for test-unit / test-it / test-ui handoff.
                    Omit the payload object for lanes that are not required.
                    """.trim();
            case IMPLEMENT_BE -> """
                    evidence.completionPayload must be:
                    {
                      "scope": "<lane scope>",
                      "task": "...",
                      "summary": "...",
                      "changedFiles": [],
                      "integrationFlows": [],
                      "persistenceChanges": [],
                      "sonar": {}
                    }
                    """.trim();
            case IMPLEMENT_FE -> """
                    evidence.completionPayload must be:
                    {
                      "scope": "<lane scope>",
                      "task": "...",
                      "summary": "...",
                      "changedFiles": [],
                      "affectedSurfaces": [],
                      "uiBehavior": [],
                      "sonar": {}
                    }
                    """.trim();
            case TEST_UNIT -> """
                    evidence.completionPayload must be:
                    {
                      "scope": "<lane scope>",
                      "task": "...",
                      "summary": "...",
                      "affectedFiles": [],
                      "sonar": {}
                    }
                    """.trim();
            case TEST_IT -> """
                    evidence.completionPayload must be:
                    {
                      "scope": "<lane scope>",
                      "summary": "...",
                      "coveredCases": []
                    }
                    """.trim();
            case TEST_UI -> """
                    evidence.completionPayload must be:
                    {
                      "scope": "<lane scope>",
                      "summary": "...",
                      "coveredCases": [],
                      "sonar": {}
                    }
                    """.trim();
            case REVIEWER -> """
                    evidence.completionPayload must be:
                    {
                      "scope": "GLOBAL"
                    }
                    """.trim();
            case EVENT -> """
                    evidence.completionPayload must be:
                    {
                      "scope": "GLOBAL"
                    }
                    """.trim();
        };
    }
}
