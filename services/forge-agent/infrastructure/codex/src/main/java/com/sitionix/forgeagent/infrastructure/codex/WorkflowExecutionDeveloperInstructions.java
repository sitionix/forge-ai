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
            - `availableOutputs`, when present, contains the output choices for this invocation.
              Each output's `name` and `description` define its business meaning; its stable `id` identifies the workflow output port.
            - When multiple outputs are available, choose exactly one according to the actual business result and return its `id` in `__forge.outputPortId`.
            - In that multi-output response, `payload` contains only the configured business output.
              `__forge` is workflow control metadata, not business data.
            - IDs such as port IDs, node-run IDs, and connection IDs are workflow provenance and must not be treated as business input values.
            - Determine the business input from your Agent instructions, the semantic input-port description, the original task when present, and delivered contribution payloads.
            - Do not infer the target business value from unrelated fields merely because they appear in the envelope.
            - Return only the structured output required by the provided output schema and execution contract.
            """;

    private WorkflowExecutionDeveloperInstructions() {
    }

    static String compose(final String agentInstructions) {
        return CONTRACT + "\n" + agentInstructions;
    }
}
