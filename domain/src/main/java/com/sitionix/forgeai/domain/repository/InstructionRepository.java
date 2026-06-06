package com.sitionix.forgeai.domain.repository;

/**
 * Provides lane strategy instruction texts.
 */
public interface InstructionRepository {

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
