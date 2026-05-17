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
}
