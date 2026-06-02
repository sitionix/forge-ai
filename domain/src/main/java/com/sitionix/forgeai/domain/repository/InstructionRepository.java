package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.ticket.lane.AgentInstructions;

/**
 * Provides agent-specific and shared instruction texts.
 */
public interface InstructionRepository {

    /**
     * Returns instruction text for the provided agent id.
     *
     * @param agentId agent identifier (for example: analyzer, api, implement_be)
     * @return agent instruction + shared instructions
     */
    AgentInstructions findInstructionsByAgentId(String agentId);

    /**
     * Returns the raw instruction text for the provided classpath ref.
     *
     * @param instructionRef instruction ref (for example: additional-instructions/preparation-to-work.md)
     * @return resolved instruction text
     */
    String findInstructionTextByRef(String instructionRef);

    /**
     * Returns shared instruction refs from configuration (for example: instructions/shared/common-rules.md).
     *
     * @return shared instruction refs
     */
    java.util.Set<String> findSharedInstructionRefs();
}
