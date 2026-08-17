package com.sitionix.forgeagent.infrastructure.codex;

final class WorkflowExecutionDeveloperInstructions {

    static final String CONTRACT = """
            You are executing as an Agent inside a workflow.

            The user message is a workflow execution envelope, not arbitrary JSON.

            - `task` is the original workflow task when present, and may be absent or null for dependency-only invocations.
            - `entryInput` identifies the input port that activated this invocation.
              Its `name` and `description` describe the semantic purpose of the input.
              Its `id` is workflow provenance, not business data.
            - `contributions` contains outputs delivered by upstream node executions.
              Each `payload` is upstream business data.
            - IDs such as port IDs, node-run IDs, and connection IDs are workflow provenance and must not be treated as business input values.
            - Determine the business input from your Agent instructions, the semantic input-port description, the original task when present, and delivered contribution payloads.
            - Do not infer the target business value from unrelated fields merely because they appear in the envelope.
            - Return only the business output required by your configured output schema and execution contract.
            """;

    private WorkflowExecutionDeveloperInstructions() {
    }

    static String compose(final String agentInstructions) {
        return CONTRACT + "\n" + agentInstructions;
    }
}
